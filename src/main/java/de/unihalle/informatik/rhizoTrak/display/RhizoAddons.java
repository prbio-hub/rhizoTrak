/* 
 * This file is part of the rhizoTrak project.
 * 
 * Note that rhizoTrak extends TrakEM2, hence, its code base substantially 
 * relies on the source code of the TrakEM2 project and the corresponding Fiji 
 * plugin, initiated by A. Cardona in 2005. Large portions of rhizoTrak's code 
 * are directly derived/copied from the source code of TrakEM2.
 * 
 * For more information on TrakEM2 please visit its websites:
 * 
 *  https://imagej.net/TrakEM2
 * 
 *  https://github.com/trakem2/TrakEM2/wiki
 * 
 * Fore more information on rhizoTrak, visit
 *
 *  https://prbio-hub.github.io/rhizoTrak
 *
 * Both projects, TrakEM2 and rhizoTrak, are released under GPL. 
 * Please find below first the copyright notice of rhizoTrak, and further on
 * (in case that this file was part of the original TrakEM2 source code base
 * and contained a TrakEM2 file header) the original file header with the 
 * TrakEM2 license note.
 */

/*
 * Copyright (C) 2018 - @YEAR@ by the rhizoTrak development team
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 * Fore more information on rhizoTrak, visit
 *
 *    https://prbio-hub.github.io/rhizoTrak
 *
 */

/* === original file header below (if any) === */

package de.unihalle.informatik.rhizoTrak.display;

import java.awt.Color;
import java.awt.Point;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.geom.AffineTransform;
import java.awt.geom.NoninvertibleTransformException;
import java.awt.geom.Point2D;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map.Entry;

import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.JComponent;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPopupMenu;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;

import de.unihalle.informatik.rhizoTrak.Project;
import de.unihalle.informatik.rhizoTrak.addon.RhizoColVis;
import de.unihalle.informatik.rhizoTrak.addon.RhizoMain;
import de.unihalle.informatik.rhizoTrak.addon.RhizoProjectConfig;
import de.unihalle.informatik.rhizoTrak.addon.RhizoUtils;
import de.unihalle.informatik.rhizoTrak.conflictManagement.ConflictManager;
import de.unihalle.informatik.rhizoTrak.display.Treeline.RadiusNode;
import de.unihalle.informatik.rhizoTrak.tree.DNDTree;
import de.unihalle.informatik.rhizoTrak.tree.ProjectThing;
import de.unihalle.informatik.rhizoTrak.tree.ProjectTree;
import de.unihalle.informatik.rhizoTrak.utils.Utils;
import ij.ImagePlus;

public class RhizoAddons
{
	private boolean debug = false;
	
	public boolean splitDialog = false;
	
	public Node<?> lastEditedOrActiveNode = null;

	private ConflictManager conflictManager = null;

	private Project project = null;
	private RhizoMain rhizoMain;

	public RhizoAddons(RhizoMain rhizoMain, Project project) 
	{
		this.rhizoMain = rhizoMain;
		this.project = project;
		this.conflictManager = new ConflictManager(rhizoMain);
	}
	
	/**
	 * Methods to lock all images/patches on project opening
	 * @author Axel
	 */
	public static void lockAllImagesInAllProjects()
	{
		List<Project> projects = Project.getProjects();
		projects.stream().forEach((project) -> {
			lockAllImageInLayerSet(project.getRootLayerSet());
		});
	}

	private static void lockAllImageInLayerSet(LayerSet layerSet)
	{
		List<Displayable> patches = layerSet.getDisplayables(Patch.class);

		if(patches.size() == 0){return;}

		patches.stream().forEach((patch) -> {
			patch.setLocked(true);
		});
	}

	

	/* displayable stuff */

	/**
	 * Copies treelines from the current layer to the next one
	 * @author Axel
	 */
	public void copyTreeLine()
	{
		Display display = Display.getFront();
		// Layer frontLayer = Display.getFrontLayer();
		Layer currentLayer = display.getLayer();
		if ( currentLayer == null ) {
			Utils.showMessage("rhizoTrak", "Copy treelines failed: internal error, can not find currentLayer.");
			return;
		}
		LayerSet currentLayerSet = currentLayer.getParent();
		
		// determine next layer
		Layer nextLayer = currentLayerSet.next(currentLayer);
		if (nextLayer == null || nextLayer.getZ()==currentLayer.getZ()) {
			Utils.showMessage("rhizoTrak", "Copy treelines failed: Can not copy from the last layer.");
			return;
		}
	
		
		// find all rootstacks
		HashSet<ProjectThing> rootstackThings = RhizoUtils.getRootstacks( project);
		if ( rootstackThings == null) {
			Utils.showMessage( "rhizoTrak", "Copy treelines failed: no rootstack found");
			return;
		}

		// check if there are any treelines in the next layer
		if(RhizoUtils.areTreelinesInLayer(rootstackThings, nextLayer))
		{
			// ask the user if he wants to delete the treelines before copying
			int answer = JOptionPane.showConfirmDialog(null, "There are treelines on the layer you are about to copy to.\nDo you want to delete them before copying?", 
					"", JOptionPane.YES_NO_OPTION);
			if(answer == JOptionPane.YES_OPTION) RhizoUtils.deleteAllTreelinesFromLayer(nextLayer, project);
		}
		
		HashMap<Long,Treeline> treelineHash = RhizoUtils.getTreelinesBelowRootstacks(rootstackThings);
		
		for(Entry<Long, Treeline> currentEntry: treelineHash.entrySet()) {
			Treeline ctree = currentEntry.getValue();
			if ( ctree.getFirstLayer() != null && currentLayer.equals( ctree.getFirstLayer()) ) {
				try {
					// copy current tree
					Treeline copy = Tree.copyAs(ctree, Treeline.class, Treeline.RadiusNode.class);
					copy.setLayer(nextLayer, true);
					for (Node<Float> cnode : copy.getRoot().getSubtreeNodes()) {
						cnode.setLayer(nextLayer);
						Color col = rhizoMain.getProjectConfig().getColorForStatus((cnode.getConfidence()));
						cnode.setColor(col);
					}
					copy.setTitle("treeline");
					copy.clearState();
					copy.updateCache();
					currentLayerSet.add(copy);
					ctree.getProject().getProjectTree().addSibling(ctree, copy);
					
					// get the parent connector; if non exists a new will be create
					//copytreelineconnector
					if(!RhizoAddons.getRightPC(ctree, copy)){
						Utils.showMessage("rhizoTrak", "Copy treelines can not create connector automatically between #" +
								ctree.getId() + " and #" + copy.getId());
					}
					Display.update(currentLayerSet);
				} catch (Exception e) {
					e.printStackTrace();
					Utils.showMessage("rhizoTrak", "Copy treelines failed for treeline #"  + ctree.getId());
				}
			}
		}
	}
	


	
	/**
	 * shortcut method for creating new treelines. Currently: "a"
	 * @author Axel
	 */
	public static void newTreelineShortcut()
	{
		// get the relevant stuff
		Display display = Display.getFront();
		Display.clearSelection();
		Project project = display.getProject();
		ProjectTree currentTree = project.getProjectTree();

		// find one rootstack
		ProjectThing rootstackProjectThing = RhizoUtils.getOneRootstack(project);
		if ( rootstackProjectThing == null ) {
			Utils.showMessage( "Create treeline: WARNING  can not find a rootstack in project tree");
			return;	
		}

		// make new treeline
		ProjectThing pt = rootstackProjectThing.createChild("treeline");
		pt.setTitle(pt.getUniqueIdentifier());
		// add new treeline to the project tree
		DefaultMutableTreeNode parentNode = DNDTree.findNode(rootstackProjectThing, currentTree);
		DefaultMutableTreeNode node = new DefaultMutableTreeNode(pt);
		((DefaultTreeModel) currentTree.getModel()).insertNodeInto(node, parentNode, parentNode.getChildCount());
	}

	/**
	 * Finds the parent connector
	 * @param ptree - Parent treeline
	 * @param ctree - Current treeline
	 * @return Success
	 * @author Axel
	 */
	public static boolean getRightPC(Treeline ptree, Treeline ctree)
	{
		//copytreelineconnector - two possibilities: a) ptree has no connector, so a new one needs to be created b) ptree has a connector so we can call copyEvent
		if (ptree.getTreeEventListener().size() < 1)
		{
			Node<Float> pTreeRoot = ptree.getRoot();
			Project project = Display.getFront().getProject();
			Connector pCon = project.getProjectTree().tryAddNewConnector(ptree, false);
			if (pCon == null)
			{
				return false;
			}
			Node<Float> newRoot = pCon.newNode(pTreeRoot.getX(), pTreeRoot.getY(), pTreeRoot.getLayer(), null);
			pCon.addNode(null, newRoot, (byte) RhizoProjectConfig.STATUS_CONNECTOR); // aeekz  
			pCon.setRoot(newRoot);
			pCon.setAffineTransform(ptree.getAffineTransform());
			boolean suc = pCon.addConTreeline(ptree);
			ptree.copyEvent(ctree);
			return suc;
		}
		else
		{
			ptree.copyEvent(ctree);
		}
		return true;
	}
	
	public static Connector giveNewConnector(Treeline target,Treeline model)
	{
		if(model == null)
		{
			model = target;
		}
		if(!target.getTreeEventListener().isEmpty())
		{
			//already own a connector
			return null;
		}
			
		Node<Float> pTreeRoot = target.getRoot();
		Project project = Display.getFront().getProject();
		Connector con = project.getProjectTree().tryAddNewConnector(model, false);
		
		if (con == null)
		{
			//something went wrong
			return null;
		}
		
		Node<Float> newRoot = con.newNode(pTreeRoot.getX(), pTreeRoot.getY(), pTreeRoot.getLayer(), null);
		con.addNode(null, newRoot, pTreeRoot.getConfidence());
		con.setRoot(newRoot);
		con.setAffineTransform(target.getAffineTransform());
		
		boolean suc = con.addConTreeline(target);
		
		if(!suc)
		{
			//something went wrong
			return null;
		}
		
		return con;
	}
	
	public static void transferConnector(Treeline donor, Treeline acceptor)
	{
		List<TreeEventListener> listenerList = new ArrayList<TreeEventListener>(donor.getTreeEventListener());
		for(TreeEventListener currentListener: listenerList)
		{
			Connector currentConnector = currentListener.getConnector();
			currentConnector.removeConTreeline(donor);
			currentConnector.addConTreeline(acceptor);
		}
	}
	
	/**
	 * Tool for merging treelines in a more convenient way
	 * @param la - Current layer
	 * @param x_p - Mouse x position
	 * @param y_p - Mouse y position
	 * @param mag - Current magnification
	 * @param anode - Selected active node
	 * @param parentTl - Selected active treeline, assumed to be non <code>null</code>
	 * @param me - MouseEvent
	 * 
	 * @author Axel
	 */
	public void mergeTool(final Layer la, final int x_p, final int y_p, double mag, RadiusNode anode, Treeline parentTl, MouseEvent me)
	{
		Thread mergeRun = new Thread() {
			{
				setPriority(Thread.NORM_PRIORITY);
			}

			@Override
			public void run() {
				Display display = Display.getFront();
				
				// check parent treeline (should not happen but to be sure)
				if (parentTl.getClass().equals(Treeline.class) == false) {
					exitMergeTool("merging treelines failed: parent is not a treeline", parentTl, display, true);
					return;
				}
				
				if(parentTl.getMarked()==null) {
					exitMergeTool("merging treelines failed: no active node in parent treeline", parentTl, display, false);
					return;
				}

				// let the user choose the child treeline
				Displayable oldActive = display.getActive(); // do not know why we can not use parentTl instead? sp

				Thread t = choose(me.getX(), me.getY(), x_p, y_p, false, Treeline.class, display);
				t.start();
				try {
					t.join();
				} catch (InterruptedException e) {
					e.printStackTrace();
					exitMergeTool( "merging treelines failed: choosing child treeline interrupted", parentTl, display, false);
					return;
				}
								
				if ( oldActive.equals(display.getActive()) ) {
					exitMergeTool("merging treelines canceled: parent and target treeline are identical", parentTl, display, true);

					return;
				}
				
				Treeline target;
				if ( display.getActive() != null && display.getActive().getClass().equals( Treeline.class) ) {
					target = (Treeline) display.getActive();
				} else {
					exitMergeTool("merging treelines canceled: no target treeline selected", parentTl, display, true);

					return;
				}
				
				DisplayCanvas dc = display.getCanvas();
				final Point po = dc.getCursorLoc();

				Node<Float> tmpNode = target.findClosestNodeW(target.getNodesToPaint(la), po.x, po.y, dc.getMagnification());
				RadiusNode nd;
				if ( tmpNode != null && tmpNode instanceof RadiusNode ) {
					nd = (RadiusNode) tmpNode;
				} else {
					exitMergeTool("merging treelines failed: found no target node on child treeline", parentTl, display, true);
					return;
				}
				
				//check if the merge create conflict and communicate with the user if the action should be continued
				int goAhead=conflictManager.mergeInteraction(parentTl, target);

				if(goAhead==0) {
					parentTl.unmark();
					Display.updateVisibleTabs();				
					Display.repaint(display.getLayerSet());
					return;
				}

				display.setActive(parentTl);
				
				//TODO: check if resolveTree or transferConnector needs target.setLastMarked(nd
				if(goAhead==1) {
					HashSet<Treeline> treelineSet = new HashSet<Treeline>();
					treelineSet.add(parentTl);
					treelineSet.add(target);

					conflictManager.resolveTree(treelineSet);
				} else 	if(goAhead==2) {
					transferConnector(target, parentTl);
				} else if ( goAhead==3 ) {
					if ( rhizoMain.getProjectConfig().isAskMergeTreelines() &&
						! Utils.checkYN("Proceed to merge treelines #" + parentTl.getId() + " and #" + target.getId()) ) {
						
						exitMergeTool( null, parentTl, display, true);
						return;
					}
				}
				
				ArrayList<Tree<Float>> joinList = new ArrayList<>();
				joinList.add(parentTl);
				target.setLastMarked(nd);
				joinList.add(target);

				parentTl.join(joinList);
				parentTl.unmark();
				
				target.remove2(false);
				display.setActive(parentTl); // obviously necessary to not have parentTl NOT displayed in the canvas

				Display.updateVisibleTabs();				
				Display.repaint(display.getLayerSet());
			};
		};
		mergeRun.start();
	}
	
	/** as we are exiting mergeTool() at many places
	 * @param msg
	 * @param parentTl
	 * @param display
	 */
	private void exitMergeTool( String msg, Treeline parentTl, Display display, boolean ummark) {
		if ( msg != null) {
			Utils.log2( msg);	
			Utils.showMessage( "rhizoTrak", msg);
		}
		if ( ummark)
			parentTl.unmark();
		Display.updateVisibleTabs();				
		Display.repaint(display.getLayerSet());
	}

		
	/**
	 * Tool for binding connectors to treelines
	 * @param la - Current layer
	 * @param x_p - Mouse x position
	 * @param y_p - Mouse y position
	 * @param mag - Current magnification
	 * @param parentConnector - Connector to be bound
	 * @param me - MouseEvent
	 * @author Axel
	 */
	public void bindConnectorToTreeline(final Layer la, final int x_p, final int y_p, double mag, Connector parentConnector, MouseEvent me)
	{
		Thread bindRun = new Thread()
		{
			{
				setPriority(Thread.NORM_PRIORITY);
			}

			@Override
			public void run()
			{
				Display display = Display.getFront();
				RhizoAddons rhizoAddons = display.getProject().getRhizoMain().getRhizoAddons();
				ConflictManager conflictManager = rhizoAddons.getConflictManager();
				DisplayCanvas dc = display.getCanvas();
				final Point po = dc.getCursorLoc();
				// Utils.log(display.getActive());
				Displayable oldActive = display.getActive();
				Thread t = choose(me.getX(), me.getY(), x_p, y_p,false, Treeline.class, display);
				t.start();
				try
				{
					t.join();
				} catch (InterruptedException e)
				{
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
				// Utils.log(display.getActive());
				if (oldActive.equals(display.getActive()))
				{
					Utils.log("found no target");
					return;
				}
				Treeline target = (Treeline) display.getActive();
				if (target == null)
				{
					Utils.log("no active Treeline found");
					return;
				}
				// check if the treeline is already connected
				for (Treeline tree : parentConnector.getConTreelines())
				{
					if (tree.equals(target))
					{
						// that should happen if the target is already connected to the Connector
						if (Utils.check("Really remove connection between " + parentConnector.getId() + " and " + target.getId() + " ?"))
						{
							parentConnector.removeConTreeline(target);
							conflictManager.processChange(target, parentConnector);
						}
						display.setActive(parentConnector);
						return;
					}
				}
				//gather the know relevant connectors
				HashSet<Connector> connectorSet = new HashSet<Connector>();
				connectorSet.add(parentConnector);
				List<TreeEventListener> tel = target.getTreeEventListener();
				for (TreeEventListener treeEventListener : tel) {
					connectorSet.add(treeEventListener.getConnector());
				}
				//add the target temporarily and check the situation then remove the target			
				int goAhead = conflictManager.addInteraction(connectorSet,target);				
				
				//act accordingly to the user reaction
				if(goAhead==1 || goAhead==2 || goAhead==3) {
					parentConnector.addConTreeline(target);
					display.setActive(parentConnector);
					conflictManager.processChange(target, parentConnector);
					if(goAhead==1) 
					{
						conflictManager.resolve(connectorSet);
					}					
				}
				return;
			};
		};
		bindRun.start();
	}
	
	
	/* other stuff */

	/**
	 * Finds a projecThing that can hold objects of the given type
	 * @param type
	 * @param project - Current project
	 * @return ProjectThing
	 */
	public static ProjectThing findParentAllowing(String type, Project project)
	{
		Enumeration<?> enum_nodes;
		enum_nodes = project.getProjectTree().getRoot().depthFirstEnumeration();
		while (enum_nodes.hasMoreElements())
		{
			DefaultMutableTreeNode currentNode = (DefaultMutableTreeNode) enum_nodes.nextElement();
			ProjectThing currentProjectThing = (ProjectThing) currentNode.getUserObject();
			if (currentProjectThing.canHaveAsChild(type))
			{
				return currentProjectThing;
			}
		}
		return null;
	}
	
	/**
	 * @author Tino
	 * @param t - a treeline
	 * @return - The patch t is displayed on; if no patches are defined in the project (probably
	 * no image loaded yet) null is returned
	 */
	public static Patch getPatch(Treeline t)
	{
		Layer layer = t.getFirstLayer();
		LayerSet layerSet = layer.getParent();
		List<Patch> patches = layerSet.getAll(Patch.class);
		
		for(Patch patch: patches)
		{
			if(patch.getLayer().getZ() == layer.getZ()) return patch;
		}
		
		return null;
	}

	

	/**
	 * Converts connector coordinates to treeline coordinates and vice versa
	 * @param x
	 * @param y
	 * @param start - Source 
	 * @param end - Target
	 * @author Axel
	 * @return Point2D of the converted coordinates
	 */
	public static Point2D changeSpace(float x, float y, AffineTransform start, AffineTransform end)
	{
		Point2D result = new Point2D.Float(x, y);
		result = start.transform(result, null);
		try {
			result = end.inverseTransform(result, null);
		} catch (NoninvertibleTransformException e) {
			// TODO Auto-generated catch block
			Utils.showMessage( "rhizoTrak", "RhizoAddons.changeSpace: internal ERROR cannot inverse transformation");
			e.printStackTrace();
			result = null;
		}
		return result;
	}

	/**
	 * For testing purposes
	 */
	public void test() 
	{
		// aeekz:
	
		// get layers
		Display display = Display.getFront();	
		LayerSet layerSet = display.getLayerSet();  
		ArrayList<Layer> layers = layerSet.getLayers();
		
		// get image names
		List<Patch> patches = layerSet.getAll(Patch.class);
		
		for(Patch p: patches)
		{
			ImagePlus image = p.getImagePlus();
			Utils.log(image.getTitle());
			Utils.log("1 pixel = " + image.getCalibration().pixelWidth + image.getCalibration().getUnits());
		}
		
		// actyc:
		
		//SplitDialog splitDialog = new SplitDialog();
		// RhizoAddons.test = !RhizoAddons.test;
		//Utils.log("Aktueller Zustand: " + RhizoAddons.test);
		//Display display = Display.getFront();
		// Layer frontLayer = Display.getFrontLayer();
		//Layer currentLayer = display.getLayer();
		//LayerSet currentLayerSet = currentLayer.getParent();
		//Project project = display.getProject();
		//project.getProjectTree();
//		Utils.log("Status: "+RhizoAddons.mergeActive);
//		// currentLayerSet.updateLayerTree();
//		// determine next layer
//		ArrayList<Displayable> trees = currentLayerSet.get(Treeline.class);
//
//		for (Displayable cObj : trees) {
//			Treeline ctree = (Treeline) cObj;
//			if (ctree.getFirstLayer() == currentLayer) {
//				try {
//					// ctree.repaint();
//					Utils.log2("current Tree first Layer: " + ctree.getFirstLayer());
//					Utils.log2("current Tree Layer Property" + cObj.getLayer());
//				} catch (Exception e) {
//					// TODO Auto-generated catch block
//					e.printStackTrace();
//				}
//
//			}
//
//		}
	}
	
	
	protected Thread choose(final int screen_x_p, final int screen_y_p, final int x_p, final int y_p, final Class<?> c, final Display currentDisplay)
	{
		return choose(screen_x_p, screen_y_p, x_p, y_p, false, c, currentDisplay);
	}

	protected Thread choose(final int screen_x_p, final int screen_y_p, final int x_p, final int y_p, final Display currentDisplay)
	{
		return choose(screen_x_p, screen_y_p, x_p, y_p, false, null, currentDisplay);
	}

	/**
	 * Find a Displayable to add to the selection under the given point (which is in offscreen coords); will use a popup menu to give the user a set of Displayable objects to select from.
	 * @param screen_x_p - Clicked global x coordinate
	 * @param screen_y_p - Clicked global y coordinate
	 * @param x_p - Clicked local x coordinate
	 * @param y_p - Clicked local y coordinate
	 * @param shift_down - Is shift pressed?
	 * @param c - Class of objects to be choosing from
	 * @param currentDisplay
	 * @return Thread created by clicking overlapping nodes
	 * @author Axel
	 */
	protected Thread choose(final int screen_x_p, final int screen_y_p, final int x_p, final int y_p, 
			final boolean shift_down, final Class<?> c, Display currentDisplay) {
		
		Utils.log("RhizoAddons.choose: x,y " + x_p + "," + y_p);
		Thread t = new Thread() {
			{
				setPriority(Thread.NORM_PRIORITY);
			}

			@Override
			public void run() {
			};
		};
		
		Layer layer = Display.getFrontLayer();

		RhizoAddons rhizoAddons = layer.getProject().getRhizoMain().getRhizoAddons();
		ConflictManager conflictManager = rhizoAddons.getConflictManager();                

		final ArrayList<Displayable> alDisplayables = new ArrayList<Displayable>(layer.find(x_p, y_p, true));
		alDisplayables.addAll(layer.getParent().findZDisplayables(layer, x_p, y_p, true)); // only visible ones

		if ( debug ) {
			System.out.println( "choose: alDisplayables");
			for ( Displayable d : alDisplayables ) {
				System.out.println( "    id" + d.getId() + "  " + d.getClass());
			}
		}
		
		//remove all displayables that don't match the given class if a class is given
		if (null != c) {
			// check if at least one of them is of class c
			// if only one is of class c, set as selected
			// else show menu
			for (final Iterator<?> it = alDisplayables.iterator(); it.hasNext();) {
				final Object ob = it.next();
				if (ob.getClass() != c)
					it.remove();
			}
		}

		if ( debug ) {
			System.out.println( "choose: alDisplayables after removing unmatching classes");
			for ( Displayable d : alDisplayables ) {
				System.out.println( "    id" + d.getId() + "  " + d.getClass());
			}
		}

		
		// actyc: remove those trees that contain a non selectable node at xp und yp
		// or connectors (if not selectable, or patches if locked
		ArrayList<Displayable> displayablesToRemove = new ArrayList<Displayable>();
		for (Displayable displayable : alDisplayables) {
			if (displayable.getClass() == Treeline.class ) 	{
				Treeline currentTreeline = (Treeline) displayable;
				double transX = x_p - currentTreeline.getAffineTransform().getTranslateX();
				double transY = y_p - currentTreeline.getAffineTransform().getTranslateY();
				Node<Float> nearestNode = currentTreeline.findNearestNode((float) transX, (float) transY, layer);
				if(nearestNode == null) {
					displayablesToRemove.add(displayable);
					continue;
				}
				if(nearestNode.getConfidence() >= 0 && 
						!rhizoMain.getProjectConfig().getStatusLabel((int) nearestNode.getConfidence()).isSelectable()) {
					displayablesToRemove.add(displayable);
				}
			} else if (displayable.getClass() == Connector.class) {
				if ( !rhizoMain.getProjectConfig().getStatusLabel( RhizoProjectConfig.STATUS_CONNECTOR).isSelectable() ) {
					displayablesToRemove.add(displayable);
				}
			} else if ( displayable.getClass()== Patch.class) {
				if(displayable.isLocked2()==true){
					displayablesToRemove.add(displayable);
				}
			}
		}
		alDisplayables.removeAll(displayablesToRemove);

		if ( debug ) {
			System.out.println( "choose: alDisplayables after filtering based on selectability and locked patches");
			for ( Displayable d : alDisplayables ) {
				System.out.println( "    id" + d.getId() + "  " + d.getClass());
			}
		}

		if (alDisplayables.isEmpty()) {
			final Displayable act = currentDisplay.getActive();
			Display.clearSelection();
			currentDisplay.getCanvas().setUpdateGraphics(true);
			// Utils.log("choose: set active to null");
			// fixing lack of repainting for unknown reasons, of the active one
			// TODO this is a temporary solution
			if (null != act) Display.repaint(layer, act, 5);
		} else if (1 == alDisplayables.size()) {
			final Displayable d = (Displayable) alDisplayables.get(0);
			if (null != c && d.getClass() != c) {
				Display.clearSelection();
				return t;
			}
			
			if(conflictManager.isSolving()) {
				if(conflictManager.isPartOfSolution(d)) {
					currentDisplay.select(d, shift_down);
				} else {
					if(conflictManager.userAbort()) {
						conflictManager.abortCurrentSolving();
						currentDisplay.select(d, shift_down);
					} else {
						return t;
					}
				}
			} else {
				currentDisplay.select(d, shift_down);
				chooseAction(d, x_p, y_p, currentDisplay);
			}
			
			// Utils.log("choose 1: set active to " + active);
		} else {
			// else, choose among the many
			return choose(screen_x_p, screen_y_p, alDisplayables, shift_down, x_p, y_p, currentDisplay);
		}
		return t;
	}
	
	private Thread choose(final int screen_x_p, final int screen_y_p, final Collection<Displayable> al, final boolean shift_down, final int x_p, final int y_p, Display currentDisplay)
	{
		// show a popup on the canvas to choose
		Thread t = new Thread()
		{
			{
				setPriority(Thread.NORM_PRIORITY);
			}

			@Override
			public void run()
			{
				final Object lock = new Object();
				final DisplayableChooser d_chooser = new DisplayableChooser(al, lock);
				final JPopupMenu pop = new JPopupMenu("Select:");
				for (final Displayable d : al)
				{
					final JMenuItem menu_item = new JMenuItem(d.toString());
					menu_item.addActionListener(d_chooser);
					pop.add(menu_item);
					// actyc: try to do something on mouse hoover
					menu_item.addMouseListener(new MouseListener()
					{

						@Override
						public void mouseReleased(MouseEvent e)
						{
							// TODO Auto-generated method stub

						}

						@Override
						public void mousePressed(MouseEvent e)
						{
							// TODO Auto-generated method stub

						}

						@Override
						public void mouseExited(MouseEvent e)
						{
							RhizoColVis.removeHighlight(d, true);
						}

						@Override
						public void mouseEntered(MouseEvent e)
						{
							RhizoColVis.highlight(d, true);

						}

						@Override
						public void mouseClicked(MouseEvent e)
						{
							// TODO Auto-generated method stub

						}

					});
				}

				SwingUtilities.invokeLater(new Runnable()
				{
					@Override
					public void run()
					{
						pop.show(currentDisplay.getCanvas(), screen_x_p, screen_y_p);
					}
				});

				// now wait until selecting something
				synchronized (lock)
				{
					do
					{
						try
						{
							lock.wait();
						} catch (final InterruptedException ie)
						{
						}
					} while (d_chooser.isWaiting() && pop.isShowing());
				}

				// grab the chosen Displayable object
				final Displayable d = d_chooser.getChosen();
				// Utils.log("Chosen: " + d.toString());
				if (null == d)
				{
					Utils.log2("Display.choose: returning a null!");
				}
				
				//check if there is a solving situation is running

				RhizoAddons rhizoAddons = currentDisplay.getProject().getRhizoMain().getRhizoAddons();
				ConflictManager conflictManager = rhizoAddons.getConflictManager();

				if(conflictManager.isSolving()){
					if(conflictManager.isPartOfSolution(d))
					{
						currentDisplay.select(d, shift_down);
					}
					else
					{
						if(conflictManager.userAbort())
						{
							conflictManager.abortCurrentSolving();
							currentDisplay.select(d, shift_down);
						}
					}
				}
				else
				{
					currentDisplay.select(d, shift_down);
					chooseAction(d, x_p, y_p, currentDisplay);
				}
				
				
				pop.setVisible(false);

				// fix selection bug: never receives mouseReleased event when
				// the popup shows
				currentDisplay.getMode().mouseReleased(null, x_p, y_p, x_p, y_p, x_p, y_p);

				// actyc: return to the original color
				RhizoColVis.removeHighlight(new ArrayList<Displayable>(al),true);

				return;
			}
		};
		return t;
	}
	
	private void chooseAction(Displayable d, float wx,float wy , Display currentDisplay) {
		if(d instanceof Treeline) {
			((Treeline) d).toClosestPaintedNode(currentDisplay.getLayer(), wx, wy, currentDisplay.getCanvas().getMagnification());
		}
	}

    
    /**
     * Filters a list of treelines by the given layer
     * @param l - Layer
     * @param treelines - List of treelines
     * @return Treelines in the given layer
     * @author Tino
     */
    public static List<Displayable> filterTreelinesByLayer(Layer l, List<Displayable> treelines)
    {
    	 List<Displayable> res = new ArrayList<Displayable>();
    	
    	for(int j = 0; j < treelines.size(); j++)
		{
			Treeline currentTreeline = (Treeline) treelines.get(j);
			
			if(null == currentTreeline.getFirstLayer()) continue;
			if(currentTreeline.getFirstLayer().equals(l)) res.add(currentTreeline);
		}
    	
    	return res;
    }
	
	public Project getProject()
	{
		return project;
	}

	public void setConflictManager(ConflictManager conflictManager) 
	{
		this.conflictManager = conflictManager;
	}

	public ConflictManager getConflictManager()
	{
		return conflictManager;
	}


}


