package com.util.raster;

import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.List;

public class X3DGrid extends XGrid{
	
	protected XBox[][] m_matrix = null;
	
	public X3DGrid(Rectangle2D.Double rect, double min_size_of_height,Point2D.Double offsetPoint){
		super(rect,min_size_of_height,offsetPoint);
    	// back up of m_boxes     	
    	this.m_matrix = new XBox[num_of_row+1][num_of_row+1];
    	for(int i=0;i<this.m_matrix.length;i++){
    		for (int j=0;j<this.m_matrix[0].length;j++){
    			this.m_matrix[i][j] = null;
    		}
    	}
	}
	
	
    /**
     * To Determine the subspace which the point belongs to. The point here is for the original point.
     * It is used to get the point's index
     * @param x
     * @param y
     * @return Box, it indicates the row and column this point belongs to and the number of objects the box has already
     */  
    public XBox locate(double x, double y) {
    	double[] normalized = this.normalize(x, y);  
    	XBox box = this.intervalLocate(normalized[0], normalized[1]);
    	return box;
    }
    
    /**
     * This is for locating the index for the point within the normailzed space 
     * The range is [), it is consistent with the scan, the stop is also the semi-interval
     * @param x
     * @param y
     * @return
     */
    private XBox intervalLocate(double x, double y){
    	
    	if(x>this.m_rect.getMaxX() || x<this.m_rect.getMinX() || y>this.m_rect.getMaxY() || y<this.m_rect.getMinY())
    		return new XBox(null,null);
    	//System.out.println("[DEGBU]interval locate: "+this.m_rect.getX() + ";"+this.m_rect.getY()+";"+this.m_rect.getWidth() + ";"+this.m_rect.getHeight());
    	    	
    	int row = (int) ((y-this.m_rect.getY()) / this.min_size_of_height );   	
    	int column = (int)((x - this.m_rect.getX()) / this.min_size_of_width );  		
    		
    	String f_row = this.IndexKeyFormatter.format(row);
    	String f_column = this.IndexColumnFormatter.format(column);
    	    	
    	
    //	Fixed, because the box does not add the number, so the raster returned the wrong result in range query
    	if(this.m_matrix[row][column] == null){
    		XBox box = new XBox(f_row,f_column);
    		this.m_matrix[row][column] = box; 
    		box.addObject();
    	}else{    		
    		this.m_matrix[row][column].addObject();
    	}   	
    	
    	return this.m_matrix[row][column];    	
    }   
    
    public XBox[] match(double x, double y,double radius){    
    	double[] normalized = this.normalize(x, y);
    	x = normalized[0];
    	y = normalized[1];    	
    	
    	double minX = (m_rect.getMinX()<(x-radius))? (x-radius):m_rect.getMinX(); 
    	double minY = (m_rect.getMinY()<(y-radius))? (y-radius):m_rect.getMinY(); 
    	double maxX = (m_rect.getMaxX()>(x+radius))? (x+radius):m_rect.getMaxX();
    	double maxY = (m_rect.getMaxY()>(y+radius))? (y+radius):m_rect.getMaxY();
    	System.out.println("match bounder: ("+minX+","+minY+")("+maxX+","+maxY+")");
    	
    	XBox tl = this.intervalLocate(minX, minY);    	
    	XBox br = this.intervalLocate(maxX, maxY);    	
    	return new XBox[]{tl,br};    	   	
    }
    
    public XBox[] intersect(Rectangle2D.Double rect)throws Exception{

    	// normalize the rect to locate it into the same coordinate with the grid
    	double[] normalized = this.normalize(rect.getX(),rect.getY());
    	Rectangle2D.Double vRect = new Rectangle2D.Double(normalized[0],normalized[1],
    			rect.getWidth(),rect.getHeight());
    	
/*    	System.out.println("Grid: "+this.m_rect.getX()+";"+this.m_rect.getY()+"==>"+this.m_rect.getWidth()+";"+this.m_rect.getHeight());
    	System.out.println("Range: "+rect.getX()+";"+rect.getY()+"==>"+rect.getWidth()+";"+rect.getHeight());
    	System.out.println("Range: "+vRect.getX()+";"+vRect.getY()+"==>"+vRect.getWidth()+";"+vRect.getHeight());
    	System.out.println("Range: "+vRect.getMinX()+";"+vRect.getMinY()+"==>"+vRect.getMaxX()+";"+vRect.getMaxY());
 */   	
    	Rectangle2D.Double dest = new Rectangle2D.Double();
    	Rectangle2D.Double.intersect(vRect, this.m_rect, dest);
/*    	System.out.println("intersection rectangle: "+dest.getMinX()+";"+dest.getMinY()+"==>"+dest.getMaxX()+";"+dest.getMaxY());
    	System.out.println("intersection rectangle: "+dest.getX()+";"+dest.getY()+"==>"+dest.getWidth()+";"+dest.getHeight());
 */   	
    	XBox tl = this.intervalLocate(dest.getMinX(), dest.getMinY());    	
    	XBox br = this.intervalLocate(dest.getMaxX(), dest.getMaxY());    	
    	
    	return new XBox[]{tl,br};      	    	
    }    
    
    public String[] getColumns(XBox top_left, XBox bottom_right){
    	List<String> columns = new ArrayList<String>();
    	System.out.println(top_left.toString());
    	System.out.println(bottom_right.toString());
    	int max = Integer.valueOf(bottom_right.getColumn());
    	for(int i=Integer.valueOf(top_left.getColumn()); i<= max; i++){
    		String c = this.IndexColumnFormatter.format(i);    	
    		columns.add(c);
    	}
    	
		String[] c = new String[columns.size()];

		c = columns.toArray(c);
		return c;
    }    

}
