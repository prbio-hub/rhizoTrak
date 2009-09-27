/**

TrakEM2 plugin for ImageJ(C).
Copyright (C) 2009 Albert Cardona.

This program is free software; you can redistribute it and/or
modify it under the terms of the GNU General Public License
as published by the Free Software Foundation (http://www.gnu.org/licenses/gpl.txt )

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program; if not, write to the Free Software
Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA. 

You may contact Albert Cardona at acardona at ini.phys.ethz.ch
Institute of Neuroinformatics, University of Zurich / ETH, Switzerland.
**/

package ini.trakem2.display;

import ij.ImagePlus;
import ij.measure.Calibration;
import ij.measure.ResultsTable;

import ini.trakem2.imaging.LayerStack;
import ini.trakem2.Project;
import ini.trakem2.utils.Bureaucrat;
import ini.trakem2.utils.IJError;
import ini.trakem2.utils.M;
import ini.trakem2.utils.ProjectToolbar;
import ini.trakem2.utils.Utils;
import ini.trakem2.utils.Worker;
import ini.trakem2.vector.VectorString3D;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Composite;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.geom.AffineTransform;
import java.awt.geom.Area;
import java.awt.geom.Point2D;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.Rectangle;
import java.awt.Shape;
import java.awt.Stroke;
import java.awt.BasicStroke;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;

/** A sequence of points ordered in a set of connected branches. */
public class Treeline extends ZDisplayable {

	protected Branch root;

	private final class Slab extends Polyline {
		Slab() {
			super(Treeline.this.project, -1, Treeline.this.title, 0, 0, Treeline.this.alpha, true, Treeline.this.color, false, Treeline.this.at);
			this.layer_set = Treeline.this.layer_set;
		}

		@Override
		public void updateBucket() {} // disabled

		@Override
		public void repaint(boolean repaint_navigator) {} // disabled
	}

	/** A branch only holds the first point if it doesn't have any parent. */
	private final class Branch {

		final Branch parent;

		HashMap<Integer,ArrayList<Branch>> branches = null;

		final Slab pline;

		Branch(Branch parent, double first_x, double first_y, long layer_id) {
			this.parent = parent;
			// Create a new Slab with an invalid id -1:
			// TODO 
			//   - each Slab could have its own bounding box, to avoid iterating all
			// Each Slab has its own AffineTransform -- passing it in the constructor merely sets its values
			this.pline = new Slab();
			this.pline.addPoint((int)first_x, (int)first_y, layer_id, 1.0);
		}

		/** Create a sub-branch at index i, with new point x,y,layer_id.
		 *  @return the new child Branch. */
		final Branch fork(int i, double x, double y, long layer_id) {
			if (null == branches) branches = new HashMap<Integer,ArrayList<Branch>>();
			Branch child = new Branch(this, x, y, layer_id);
			ArrayList<Branch> list = branches.get(i);
			if (null == list) {
				list = new ArrayList<Branch>();
				branches.put(i, list);
			}
			list.add(child);
			return child;
		}

		/** Paint recursively into branches. */
		final void paint(final Graphics2D g, final double magnification, final boolean active, final int channels, final Layer active_layer, final Stroke branch_stroke) {
			Utils.log2("affine: " + this.pline.getAffineTransform());
			Utils.log2(Treeline.this.at == this.pline.getAffineTransform());
			this.pline.paint(g, magnification, active, channels, active_layer);
			if (null == branches) return;
			for (final Map.Entry<Integer,ArrayList<Branch>> e : branches.entrySet()) {
				final int i = e.getKey();
				final Point2D.Double po1 = Treeline.this.transformPoint(pline.p[0][i], pline.p[1][i]);
				for (final Branch b : e.getValue()) {
					b.paint(g, magnification, active, channels, active_layer, branch_stroke);
					// Paint from i in this.pline to 0 in b.pline
					final Point2D.Double po2 = Treeline.this.transformPoint(b.pline.p[0][0], b.pline.p[1][0]);
					g.setColor(Treeline.this.color);
					Stroke st = g.getStroke();
					if (null != branch_stroke) g.setStroke(branch_stroke);
					g.drawLine((int)po1.x, (int)po1.y, (int)po2.x, (int)po2.y);
					g.setStroke(st); // restore
				}
			}
		}
		final boolean intersects(final Area area, final double z_first, final double z_last) {
			if (null != pline && pline.intersects(area, z_first, z_last)) return true;
			if (null == branches) return false;
			for (final Map.Entry<Integer,ArrayList<Branch>> e : branches.entrySet()) {
				for (final Branch b : e.getValue()) {
					if (b.intersects(area, z_first, z_last)) {
						return true;
					}
				}
			}
			return false;
		}
		final boolean linkPatches() {
			boolean must_lock = null != pline && pline.linkPatches();
			for (final Map.Entry<Integer,ArrayList<Branch>> e : branches.entrySet()) {
				for (final Branch b : e.getValue()) {
					must_lock = must_lock || b.linkPatches();
				}
			}
			return must_lock;
		}
		/** Return min_x, min_y, max_x, max_y of all nested Slab. */
		final double[] calculateDataBoundingBox(double[] m) {
			if (null == pline) return m;
			final double[] mp = pline.calculateDataBoundingBox();
			Utils.log2("pline.mp: " + Utils.toString(mp));
			if (null == m) {
				m = mp;
			} else {
				m[0] = Math.min(m[0], mp[0]);
				m[1] = Math.min(m[1], mp[1]);
				m[2] = Math.max(m[2], mp[2]);
				m[3] = Math.max(m[3], mp[3]);
			}
			if (null == branches) return m;
			for (final Map.Entry<Integer,ArrayList<Branch>> e : branches.entrySet()) {
				for (final Branch b : e.getValue()) {
					m = b.calculateDataBoundingBox(m);
				}
			}
			return m;
		}
		/** Subtract x,y from all points of all nested Slab. */
		final void subtract(final double min_x, final double min_y) {
			if (null == pline) return;
			for (int i=0; i<pline.n_points; i++) {
				pline.p[0][i] -= min_x;
				pline.p[1][i] -= min_y;
			}
			if (null == branches) return;
			for (final Map.Entry<Integer,ArrayList<Branch>> e : branches.entrySet()) {
				for (final Branch b : e.getValue()) {
					b.subtract(min_x, min_y);
				}
			}
		}
		/** Return the lowest Z Layer of all nested Slab. */
		final Layer getFirstLayer() {
			Layer first = pline.getFirstLayer();
			if (null == branches) return first;
			for (final Map.Entry<Integer,ArrayList<Branch>> e : branches.entrySet()) {
				for (final Branch b : e.getValue()) {
					final Layer la = b.getFirstLayer();
					if (la.getZ() < first.getZ()) first = la;
				}
			}
			return first;
		}

		final void setAffineTransform(AffineTransform at) {
			pline.setAffineTransform(at);
			if (null == branches) return;
			for (final Map.Entry<Integer,ArrayList<Branch>> e : branches.entrySet()) {
				for (final Branch b : e.getValue()) {
					b.setAffineTransform(at);
				}
			}
		}

		/** Returns the Slab for which x_l,y_l is closest to either its 0 or its N-1 point in 3D space. */
		final Branch findClosestEndPoint(final double x_l, final double y_l, final long layer_id) {
			Branch bmin = null;
			double[] dmin = null;
			for (final Branch b : getAllBranches()) {
				final double[] d = b.pline.sqDistanceToEndPoints(x_l, y_l, layer_id);
				if (null == dmin || d[0] < dmin[0] || d[1] < dmin[1]) {
					dmin = d;
					bmin = b;
				}
			}
			return bmin;
		}
		final List<Branch> getAllBranches() {
			final ArrayList<Branch> all = new ArrayList<Branch>();
			getAllBranches(all);
			return all;
		}
		/** Ordered depth-first. */
		final void getAllBranches(final List<Branch> all) {
			all.add(this);
			if (null == branches) return;
			for (final Map.Entry<Integer,ArrayList<Branch>> e : branches.entrySet()) {
				for (final Branch b : e.getValue()) {
					b.getAllBranches(all);
				}
			}
		}
		/** Depth-first search.
		 *  List[0] = Branch
		 *  List[1] = Integer */
		final List findPoint(final int x_pl, final int y_pl, final long layer_id, final double magnification) {
			final ArrayList pi = new ArrayList();
			findPoint(x_pl, y_pl, layer_id, magnification, pi);
			return pi;
		}
		/** Depth-first search. */
		final private void findPoint(final int x_pl, final int y_pl, final long layer_id, final double magnification, final List pi) {
			int i = pline.findPoint(x_pl, y_pl, layer_id, magnification);
			if (-1 != i) {
				pi.add(this);
				pi.add(i);
				return;
			}
			if (null == branches) return;
			for (final Map.Entry<Integer,ArrayList<Branch>> e : branches.entrySet()) {
				for (final Branch b : e.getValue()) {
					b.findPoint(x_pl, y_pl, layer_id, magnification, pi);
				}
			}
		}
	}

	public Treeline(Project project, String title) {
		super(project, title, 0, 0);
		addToDatabase();
	}

	public Treeline(final Project project, final long id, final HashMap ht_attr, final HashMap ht_links) {
		super(project, id, ht_attr, ht_links);
		// parse specific data
		for (Iterator it = ht_attr.entrySet().iterator(); it.hasNext(); ) {
			// TODO
		}
	}

	static private final BasicStroke DASHED_STROKE = new BasicStroke(1, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 3, new float[]{ 30, 10, 10, 10 }, 0);

	final public void paint(Graphics2D g, final double magnification, final boolean active, final int channels, final Layer active_layer) {
		if (null == root) {
			setupForDisplay();
			if (null == root) return;
		}

		//arrange transparency
		Composite original_composite = null;
		if (alpha != 1.0f) {
			original_composite = g.getComposite();
			g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha));
		}

		root.paint(g, magnification, active, channels, active_layer, DASHED_STROKE);

		g.draw(getBoundingBox());

		//Transparency: fix alpha composite back to original.
		if (null != original_composite) {
			g.setComposite(original_composite);
		}
	}

	synchronized protected void calculateBoundingBox(final boolean adjust_position) {
		// Call calculateDataBoundingBox for each Branch and find out absolute min,max. All points are local to this TreeLine AffineTransform.
		if (null == root) return;
		final double[] m = root.calculateDataBoundingBox(null);
		if (null == m) return;

		this.width = m[2] - m[0];  // max_x - min_x;
		this.height = m[3] - m[1]; // max_y - min_y;

		Utils.log2("w, h: " + this.width + ", " + this.height);

		Utils.log2(m);

		if (adjust_position) {
			// now readjust points to make min_x,min_y be the x,y
			root.subtract(m[0], m[1]);
			this.at.translate(m[0], m[1]) ; // (min_x, min_y); // not using super.translate(...) because a preConcatenation is not needed; here we deal with the data.
			root.setAffineTransform(this.at);
			updateInDatabase("transform");
		}
		updateInDatabase("dimensions");

		layer_set.updateBucket(this);

		Utils.log2("Treeline box: " + getBoundingBox());
	}

	public void repaint() {
		repaint(true);
	}

	/**Repaints in the given ImageCanvas only the area corresponding to the bounding box of this Pipe. */
	public void repaint(boolean repaint_navigator) {
		//TODO: this could be further optimized to repaint the bounding box of the last modified segments, i.e. the previous and next set of interpolated points of any given backbone point. This would be trivial if each segment of the Bezier curve was an object.
		Rectangle box = getBoundingBox(null);
		calculateBoundingBox(true);
		box.add(getBoundingBox(null));
		Display.repaint(layer_set, this, box, 5, repaint_navigator);
	}

	/**Make this object ready to be painted.*/
	synchronized private void setupForDisplay() {
		// TODO
	}

	public boolean intersects(final Area area, final double z_first, final double z_last) {
		return null == root ? false
				    : root.intersects(area, z_first, z_last);
	}

	public Layer getFirstLayer() {
		if (null == root) return null;
		return root.getFirstLayer();
	}

	public boolean linkPatches() {
		if (null == root) return false;
		return root.linkPatches();
	}

	public Displayable clone(final Project pr, final boolean copy_id) {
		// TODO
		return null;
	}

	public boolean isDeletable() {
		return null == root || null == root.pline;
	}

	public void mousePressed(MouseEvent me, int x_p, int y_p, double mag) {
		if (ProjectToolbar.PEN != ProjectToolbar.getToolId()) {
			return;
		}
		final long layer_id = Display.getFrontLayer(this.project).getId();
		// transform the x_p, y_p to the local coordinates
		int x_pl = x_p;
		int y_pl = y_p;
		if (!this.at.isIdentity()) {
			final Point2D.Double po = inverseTransformPoint(x_p, y_p);
			x_pl = (int)po.x;
			y_pl = (int)po.y;
		}

		if (null != root) {
			Branch branch = null;
			int i = -1;
			final List pi = root.findPoint(x_pl, y_pl, layer_id, mag);
			if (2 == pi.size()) {
				branch = (Branch)pi.get(0);
				i = ((Integer)pi.get(1)).intValue();
			}
			if (-1 != i) {
				if (me.isShiftDown()) {
					if (Utils.isControlDown(me)) {
						// Remove point, and associated branches
						if (layer_id == branch.pline.p_layer[i]) {
							if (null != branch.parent && null != branch.parent.branches) {
								branch.parent.branches.remove(i);
							}
							branch.pline.removePoint(i);
							repaint(false); // keep larger size for repainting, will call calculateBoundingBox on mouseRelesed
							active = null;
							index = -1;
							return;
						}
					}
					// Create new branch at point, with local coordinates
					active = branch.fork(i, x_pl, y_pl, layer_id);
					index = 0;
					return;
				}
				// Setup point i to be dragged
				index = i;
				active = branch;
				return;
			} else {
				// Add new point
				// Find the point closest to any other starting or ending point in all branches
				Branch b = root.findClosestEndPoint(x_pl, y_pl, layer_id);
				index = -1;
				active = b;
				b.pline.addPoint(x_pl, y_pl, layer_id, mag);
				repaint(true);
				return;
			}
		} else {
			root = new Branch(null, x_pl, y_pl, layer_id);
		}
	}

	private Branch active = null;
	private int index = -1;

	public void mouseDragged(MouseEvent me, int x_p, int y_p, int x_d, int y_d, int x_d_old, int y_d_old) {
		if (null == active) return;

		// transform to the local coordinates
		if (!this.at.isIdentity()) {
			final Point2D.Double pd = inverseTransformPoint(x_d, y_d);
			x_d = (int)pd.x;
			y_d = (int)pd.y;
			final Point2D.Double pdo = inverseTransformPoint(x_d_old, y_d_old);
			x_d_old = (int)pdo.x;
			y_d_old = (int)pdo.y;
		}
		active.pline.dragPoint(index, x_d - x_d_old, y_d - y_d_old);
		repaint(false);
	}

	public void mouseReleased(MouseEvent me, int x_p, int y_p, int x_d, int y_d, int x_r, int y_r) {
		final int tool = ProjectToolbar.getToolId();

		if (ProjectToolbar.PEN == tool || ProjectToolbar.PENCIL == tool) {
			repaint(true); //needed at least for the removePoint
		}

		if (-1 == index || null == active) return;

		active.pline.mouseReleased(me, x_p, y_p, x_d, y_d, x_r, y_r);
		repaint();

		active = null;
		index = -1;
	}

	/** Call super and propagate to all branches. */
	public void setAffineTransform(AffineTransform at) {
		super.setAffineTransform(at);
		if (null != root) root.setAffineTransform(at);
	}

	/** Call super and propagate to all branches. */
	public void preTransform(final AffineTransform affine, final boolean linked) {
		super.preTransform(affine, linked);
		if (null != root) root.setAffineTransform(this.at);
	}
}
