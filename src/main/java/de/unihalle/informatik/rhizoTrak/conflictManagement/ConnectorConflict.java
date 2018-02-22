package de.unihalle.informatik.rhizoTrak.conflictManagement;

import java.util.ArrayList;

import de.unihalle.informatik.rhizoTrak.display.Connector;
import de.unihalle.informatik.rhizoTrak.display.TreeEventListener;
import de.unihalle.informatik.rhizoTrak.display.Treeline;

public class ConnectorConflict extends Conflict{
	Treeline conflictTree;
	ArrayList<Connector> connectorList;
	
	ConnectorConflict(Treeline tree, ArrayList<Connector> connectorList){
		conflictTree = tree;
		this.connectorList = connectorList;
	}

	ConnectorConflict(Treeline tree){
		conflictTree = tree;
		update();
	}
	
	/**
	 * @return the conflictTree
	 */
	public Treeline getConflictTree() {
		update();
		return conflictTree;
	}
	
	/**
	 * @return the connectorList
	 */
	public ArrayList<Connector> getConnectorList() {
		return connectorList;
	}


	private String getConnectorAsString()
	{
		String result = " Connectors: ";
		for (Connector connector : connectorList) {
			result = result + connector.getId() + "; ";
		}
		return result;
	}
	
	public void update()
	{
		//fetch the connector list
		ArrayList<Connector> connectorList = new ArrayList<Connector>();
		for(TreeEventListener listener: conflictTree.getTreeEventListener())
		{
			connectorList.add(listener.getConnector());
		}
		this.connectorList=connectorList;
		
		if(this.connectorList.size()<2)
		{
			//no real issue here so remove its self
			ConflictManager.removeConnectorConflict(conflictTree);
		}
	}


	@Override 
	public String toString(){
		return "Multiple Connector Conflict on Tree: "+ conflictTree.getId() + getConnectorAsString();
	}
	
	private boolean stillExists()
	{
		boolean result = true;
		return result;
	}
	
}