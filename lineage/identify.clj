(ns lineage.identify
  (:import
     (lineage LineageClassifier)
     (java.io InputStreamReader FileInputStream PushbackReader)
     (javax.vecmath Point3d)
     (javax.swing JTable JFrame JScrollPane JPanel JLabel BoxLayout JPopupMenu JMenuItem)
     (javax.swing.table AbstractTableModel DefaultTableCellRenderer)
     (java.awt.event MouseAdapter ActionListener)
     (java.awt Color Dimension Component)
     (mpicbg.models AffineModel3D)
     (ij.measure Calibration)
     (ini.trakem2.utils Utils)
     (ini.trakem2.display Display Display3D LayerSet)
     (ini.trakem2.vector Compare VectorString3D Editions)))

;(import
;     '(lineage LineageClassifier)
;     '(java.io InputStreamReader)
;     '(ini.trakem2.vector Compare VectorString3D))

(defn as-VectorString3D
  "Convert a map of {\"name\" {:x (...) :y (...) :z (...)}} into a map of {\"name\" (VectorString3D. ...)}"
  [SATs]
  (map
    (fn [e]
      {(key e)
       (let [data (val e)]
         (VectorString3D. (into-array Double/TYPE (data :x))
                          (into-array Double/TYPE (data :y))
                          (into-array Double/TYPE (data :z))
                          false))})
    SATs))

(defn load-SATs-lib
  "Returns the SATs library with VectorString3D instances"
  []
  (reduce
    (fn [m e]
      (let [label (key e)
            data (val e)]
        (assoc m
               label
               (assoc data :SATs (as-VectorString3D (data :SATs))))))
    {}
    (read
      (PushbackReader.
      ; (InputStreamReader. (LineageClassifier/getResourceAsStream "/lineages/SAT-lib.clj"))  ; TESTING
        (InputStreamReader. (FileInputStream. "/home/albert/lab/confocal/L3_lineages/SAT-lib-with-mb.clj"))
        4096))))

(defn fids-as-Point3d
  "Convert a map like {\"blva_joint\" [70.62114008906023 140.07819545137772 -78.72010436407604] ...}
  into a sorted map of {\"blva_joint\" (Point3d ...)}"
  [fids]
  (reduce
    (fn [m e]
      (let [xyz (val e)]
        (assoc m (key e) (Point3d. (double (get xyz 0))
                                   (double (get xyz 1))
                                   (double (get xyz 2))))))
    {}
    fids))

(defn register-SATs
  "Returns a map of SAT name vs VectorString3D,
   where the VectorString3D is registered to the target fids."
  [brain-label brain-data target-fids]
  (let [source-fids (fids-as-Point3d (brain-data :fiducials))
        SATs (into (sorted-map) (brain-data :SATs))
        common-fid-keys (clojure.set/intersection (into #{} (keys source-fids)) (into #{} (keys target-fids)))
        vs (Compare/transferVectorStrings (vals SATs)
                                          (vals (into (sorted-map) (select-keys source-fids common-fid-keys)))
                                          (vals (into (sorted-map) (select-keys target-fids common-fid-keys)))
                                          AffineModel3D)]
    (zipmap
      (map #(str (.replaceAll % "\\[.*\\] " " [") \space brain-label \]) (keys SATs))
      vs)))

(defn prepare-SAT-lib
  "Returns the SATs library as a map of name keys and VectorString3D values
  Will define the FRT42-fids to be used as reference fibs for all."
  []
  (let [SATs-lib (load-SATs-lib)
        target-fids (do
                      (def FRT42-fids (fids-as-Point3d ((SATs-lib "FRT42 new") :fiducials)))
                      FRT42-fids)]
    (reduce
      (fn [m e]
        (conj m (register-SATs (key e) (val e) target-fids)))
      {}
      SATs-lib)))

(def SAT-lib (prepare-SAT-lib))

(def
  #^{:doc "The mushroom body lobes for FRT42 brain."}
  mb-FRT42
  (let [r #"peduncle . (dorsal|medial) lobe.*FRT42 new.*"]
    (loop [sl SAT-lib
           mb {}]
      (if (= 2 (count mb))
        mb
        (if-let [[k v] (first sl)]
          (recur (next sl)
                 (if (re-matches r k)
                   (into mb {k v})
                   mb)))))))

(defn register-vs
  "Register a singe VectorString3D from source-fids to target-fids."
  [vs source-fids target-fids]
  (let [common-fid-keys (clojure.set/intersection (into #{} (keys source-fids)) (into #{} (keys target-fids)))]
    (if (empty? common-fid-keys)
      nil
      (first (Compare/transferVectorStrings [vs]
                                     (vals (into (sorted-map) (select-keys source-fids common-fid-keys)))
                                     (vals (into (sorted-map) (select-keys target-fids common-fid-keys)))
                                     AffineModel3D)))))

(defn resample
  "Returns a resampled copy of vs."
  [vs delta]
  (let [copy (.clone vs)]
    (.resample copy delta)
    copy))

(defn match-all
  "Returns a vector of two elements; the first element is the list of matches
  of the query-vs against all SAT vs in the library sorted by mean euclidean distance,
  and labeled as correct of incorrect matches according to the Random Forest classifier.
  The second element is a list of corresponding SAT names for each match."
  [query-vs delta direct substring]
  (let [vs1 (resample query-vs delta)   ; query-vs is already registered into FRT42-fids
        matches (sort
                  (proxy [java.util.Comparator] []
                    (equals [o]
                      (= o this))
                    (compare [o1 o2]
                      (int (- (o1 :med) (o2 :med)))))
                  (map
                    (fn [e]
                      (let [vs2 (let [copy (.clone (val e))]
                                  (.resample copy delta)
                                  copy)
                            c (Compare/findBestMatch vs1 vs2
                                                     (double delta) false (int 5) (float 0.5) Compare/AVG_PHYS_DIST
                                                     direct substring
                                                     (double 1.1) (double 1.1) (double 1))
                            stats (.getStatistics (get c 0) false (int 0) (float 0) false)]
                        {:SAT-name (key e)
                         :stats stats
                         :med (get stats 0)
                         :correct (LineageClassifier/classify stats)}))
                    SAT-lib))]

    [matches
     (map (fn [match]
            (match :SAT-name))
            matches)]))

(defn text-width
  "Measure the width, in pixels, of the String text, by the Font and FontMetrics of component."
  [#^String text #^Component component]
  ; Get the int[] of widths of the first 256 chars
  (let [#^ints ws (.getWidths (.getFontMetrics component (.getFont component)))]
    (reduce (fn [sum c]
              (if (< (int c) (int 256))
                (+ sum (aget ws (int c)))
                sum))
            0
            text)))

(def worker (agent nil))

(defn identify-SAT
  "Takes a calibrated VectorString3D and a list of fiducial points, and checks against the library for identity.
  For consistency in the usage of the Random Forest classifier, the registration is done into the FRT42D-BP106 brain."
  [query-vs fids delta direct substring]
  (let [vs1 (register-vs query-vs fids FRT42-fids)
        [matches names] (match-all vs1 delta direct substring)
        SAT-names (vec names)
        indexed (vec matches)
        column-names ["SAT" "Match" "Seq sim %" "Lev Dist" "Med Dist" "Avg Dist" "Cum Dist" "Std Dev" "Prop Mut" "Prop Lengths" "Proximity" "Prox Mut" "Tortuosity"]
        table (JTable. (proxy [AbstractTableModel] []
                (getColumnName [col]
                  (get column-names col))
                (getRowCount []
                  (count matches))
                (getColumnCount []
                  (count column-names))
                (getValueAt [row col]
                  (let [match (get indexed row)
                        stats (match :stats)]
                    (cond
                      (= col 0) (get SAT-names row)
                      (= col 1) (str (match :correct))    ; Whether the classifier considered it correct or not
                      true (Utils/cutNumber
                            (cond
                              (= col 2) (* 100 (get stats 6))       ; Similarity
                              (= col 3) (get stats 5)       ; Levenshtein
                              (= col 4) (get stats 3)       ; Median Physical Distance
                              (= col 5) (get stats 0)       ; Average Physical Distance
                              (= col 6) (get stats 1)       ; Cummulative Physical Distance
                              (= col 7) (get stats 2)       ; Std Dev
                              (= col 8) (get stats 4)       ; Prop Mut
                              (= col 9) (get stats 9)       ; Prop Lengths
                              (= col 10) (get stats 7)      ; Proximity
                              (= col 11) (get stats 8)      ; Prox Mut
                              (= col 12) (get stats 10))    ; Tortuosity
                            2))))
                (isCellEditable [row col]
                  false)
                (setValueAt [ob row col] nil)))
        frame (JFrame. "Matches")
        dummy-ls (LayerSet. (.. Display getFront getProject) (long -1) "Dummy" (double 0) (double 0) (double 0) (double 0) (double 0) (double 512) (double 512) false (int 0) (java.awt.geom.AffineTransform.))]
    (.setCellRenderer (.getColumn table "Match")
                      (proxy [DefaultTableCellRenderer] []
                        (getTableCellRendererComponent [t v sel foc row col]
                          (proxy-super setText (str v))
                          (proxy-super setBackground
                                          (if (Boolean/parseBoolean v)
                                            (Color. 166 255 166)
                                            (if sel
                                              (Color. 184 207 229)
                                              Color/white)))
                                              
                          this)))
    (.add frame (JScrollPane. table))
    (.setSize frame (int 950) (int 550))
    (.addMouseListener table
                       (proxy [MouseAdapter] []
                         (mousePressed [ev]
                           (send-off worker
                             (fn [_]
                               (let [match (indexed (.rowAtPoint table (.getPoint ev)))
                                     show-match (fn []
                                                  (Display3D/addMesh dummy-ls
                                                                     (resample (.clone vs1) delta)
                                                                     "Query"
                                                                     Color/yellow)
                                                  (Display3D/addMesh dummy-ls
                                                                     (resample (SAT-lib (match :SAT-name)) delta)
                                                                     (match :SAT-name)
                                                                     (if (match :correct)
                                                                       Color/red
                                                                       Color/blue)))]
                                 (cond
                                   ; On double-click, show 3D view of the match:
                                   (= 2 (.getClickCount ev))
                                     (show-match)
                                   ; On right-click, show menu
                                   (Utils/isPopupTrigger ev)
                                     (let [popup (JPopupMenu.)
                                           new-command (fn [title action]
                                                         (let [item (JMenuItem. title)]
                                                           (.addActionListener item (proxy [ActionListener] []
                                                                                      (actionPerformed [evt]
                                                                                        (send-off worker (fn [_] (action))))))
                                                           item))]
                                       (doto popup
                                         (.add (new-command "Show match in 3D"
                                                            show-match))
                                         (.add (new-command "Show Mushroom body"
                                                            #(doseq [[k v] mb-FRT42]
                                                              (Display3D/addMesh dummy-ls v k Color/gray))))
                                         (.add (new-command "Show interpolated"
                                                            #(Display3D/addMesh dummy-ls
                                                                                (VectorString3D/createInterpolatedPoints
                                                                                  (Editions. (SAT-lib (match :SAT-name)) (.clone vs1) delta false (double 1.1) (double 1.1) (double 1)) (float 0.5))
                                                                                (str "Interpolated with " (match :SAT-name)) Color/magenta)))
                                         (.add (new-command "Show stdDev plot"
                                                            #(let [cp (ini.trakem2.vector.Compare$CATAParameters.)]
                                                              (if (.setup cp false nil true true)
                                                                (.show
                                                                  (Compare/makePlot cp
                                                                                    (str "Query versus " (match :SAT-name))
                                                                                    (let [cal (Calibration.) ; Dummy calibration with microns as units. VectorString3D instances are already calibrated.
                                                                                          condensed (VectorString3D/createInterpolatedPoints
                                                                                              (let [v1 (.clone vs1)
                                                                                                    v2 (SAT-lib (match :SAT-name))]
                                                                                                    (.resample v1 delta true)
                                                                                                    (.resample v2 delta true)
                                                                                                    (Editions. v1 v2 delta false (double 1.1) (double 1.1) (double 1)))
                                                                                              (float 0.5))]
                                                                                      (.setUnit cal "micron")
                                                                                      (.calibrate condensed cal)
                                                                                      condensed)))))))
                                         (.show table (.getX ev) (.getY ev)))))))))))

    ; Enlarge the cell width of the first column
    (.setMinWidth (.. table getColumnModel (getColumn 0)) (int 250))
    (doto frame
      ;(.pack)
      (.setVisible true))))

(defn identify
  "Identify a Pipe or Polyline (which implement Line3D) that represent a SAT."
  ([p]
    (identify 1.0 true false))
  ([p delta direct substring]
    (identify-SAT
      (let [vs (.asVectorString3D p)]
            (.calibrate vs (.. p getLayerSet getCalibrationCopy))
            vs)
      (Compare/extractPoints (first (.. p getProject getRootProjectThing (findChildrenOfTypeR "fiducial_points"))))
      delta
      direct
      substring)))


(defn quantify-match
  "Take all pipes in project and score/classify them.
  Returns a sorted map of name vs. a vector with:
  - if the top 1,2,3,4,5 have a homonymous
  - the number of positives: 'true' and homonymous
  - the number of false positives: 'true' and not homonymous
  - the number of false negatives: 'false' and homonymous
  - the number of true negatives: 'false' and not homonymous
  - the FPR: false positive rate: false positives / ( false positives + true negatives )
  - the FNR: false negative rate: false negatives / ( false negatives + true positives )
  - the TPR: true positive rate: 1 - FNR
  - the length of the sequence queried"
  [project regex-exclude delta direct substring]
  (let [fids (Compare/extractPoints (first (.. project getRootProjectThing (findChildrenOfTypeR "fiducial_points"))))]
    (reduce
      (fn [m chain]
        (let [[matches names] (match-all (.vs chain) delta direct substring)
              #^String SAT-name (.getCellTitle chain)
              ;has-top-match (fn [n] (some #(.startsWith % SAT-name) (take names n)))
              top-matches (loop [n 5
                                 i 0
                                 r []]
                            (if (.startsWith (names i) SAT-name)
                              (into r (repeat (- n i) true))  ; the rest are all true
                              (recur n (inc i) (into [false] r))))
              true-positives (filter #(and (% :correct) (.startsWith (% :SAT-name) SAT-name)) matches)
              true-negatives (filter #(and (not (% :correct)) (not (.startsWith (% :SAT-name) SAT-name))) matches)
              false-positives (filter #(not (.startsWith (% :SAT-name) SAT-name)) positives)
              false-negatives (filter #(.startsWith (% :SAT-name) SAT-name) negatives)]
          (assoc m
            (.substring SAT-name (int 0) (int (.indexOf SAT-name (int \space))))
            [(top-matches 1)
             (top-matches 2)
             (top-matches 3)
             (top-matches 4)
             (top-matches 5)
             (count true-positives)
             (count false-positives)
             (count true-negatives)
             (count false-negatives)
             (/ (count false-positives) (+ (count false-positives) (count true-negatives))) ; False positive rate
             (/ (count false-negatives) (+ (count false-negatives) (count true-positives))) ; False negative rate
             (- 1 (/ (count false-negatives) (+ (count false-negatives) (count true-positives))))]))) ; True positive rate
      (sorted-map)
      (Compare/createPipeChains (.getRootProjectThing project) (.getRootLayerSet project) regex-exclude))))
