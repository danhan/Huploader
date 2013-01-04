package com.query.bixi;

import java.awt.geom.Point2D;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.List;
import java.util.NavigableMap;
import java.util.TreeMap;

import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.client.ResultScanner;
import org.apache.hadoop.hbase.filter.Filter;
import org.apache.hadoop.hbase.filter.FilterList;
import org.apache.hadoop.hbase.util.Bytes;

import com.query.QueryAbstraction;
import com.util.XTableSchema;
import com.util.raster.XBox;

public class BixiQuery4Hybrid extends QueryAbstraction{

	
	public BixiQuery4Hybrid(String schema_desc_file,String csv_desc_file,String hbase_conf){
		super(schema_desc_file,csv_desc_file,hbase_conf);
	}
	
	public BixiQuery4Hybrid(XTableSchema schema,String csv_desc_file,String hbase_conf){	
		super(schema,csv_desc_file,hbase_conf);
	}	

	
	@Override
	public List<String> copQueryAvailableNear(String timestamp,
			double latitude, double longitude, double radius) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public HashMap<String, String> scanQueryAvailableNear(String timestamp,
			double latitude, double longitude, double radius) {
		// return result
		HashMap<String, String> results = new HashMap<String, String>();
		try {
			
			this.getCSVLog(0);					
			this.timePhase.clear();
			long sTime = System.currentTimeMillis();
			this.timePhase.add(sTime);

			Point2D.Double point = new Point2D.Double(latitude, longitude);
			ResultScanner rScanner = null;			
			
			// match rect to find the subspace it belongs to
			long match_s = System.currentTimeMillis();			
			Hashtable<String,XBox[]> result = this.hybrid.match(latitude, longitude, radius);
			long match_time = System.currentTimeMillis() - match_s;
			
			// format the key ranges and column ranges			
			Hashtable<String,String[]> organizedKeys = this.reOrganizeKeys(result);
			ArrayList<String> tempArray = new ArrayList<String>(organizedKeys.keySet());
			Collections.sort(tempArray);
			
			// get the row range
			String[] rowRange = new String[2];
			rowRange[0] = (String)tempArray.get(0);
			rowRange[1] = tempArray.get(tempArray.size()-1)+"-*";
			System.out.println("row Range: "+rowRange[0]+","+rowRange[1]);
			
			// prepare filter for scan
			FilterList fList = new FilterList(FilterList.Operator.MUST_PASS_ONE);			
			for(String s: result.keySet()){							
				if(s!=null){					
					String top = s + "-" + result.get(s)[0].getRow();
					String down = s + "-" + result.get(s)[1].getRow();
					Filter rowTopFilter = hbase.getBinaryFilter(">=", top);
					Filter rowDownFilter = hbase.getBinaryFilter("<=", down);
					// the column name is the combination of columnId and Object Id. This is to avoid using the version dimension to store the objects
					//Filter columnFilter = hbase.getColumnRangeFilter((result.get(s)[0].getColumn()+"-").getBytes(),true, (result.get(s)[0].getColumn()+"-*").getBytes(),true);
					
					FilterList subList = new FilterList(FilterList.Operator.MUST_PASS_ALL);	
					//subList.addFilter(columnFilter);
					subList.addFilter(rowTopFilter);
					subList.addFilter(rowDownFilter);
					
					fList.addFilter(subList);
				}				
			}
			// the version here is harded coded, because i cannot get how many
			// objects in one cell now
			this.timePhase.add(System.currentTimeMillis());
			rScanner = this.hbase.getResultSet(rowRange, fList,
					new String[]{this.tableSchema.getFamilyName()}, null, this.tableSchema.getMaxVersions());
			
			int count = 0;
			int row = 0;
			int accepted = 0;

			for (Result r : rScanner) {
				
				row++;
				
				NavigableMap<byte[], NavigableMap<byte[], NavigableMap<Long, byte[]>>> resultMap = r
						.getMap();

				for (byte[] family : resultMap.keySet()) {
					NavigableMap<byte[], NavigableMap<Long, byte[]>> columns = resultMap
							.get(family);
					for (byte[] col : columns.keySet()) {
						
						count++;
						
						NavigableMap<Long, byte[]> values = columns.get(col);
												
						String id = Bytes.toString(values.get((Long.valueOf(1))));
						// Bytes.toDouble cannot be used
						double lat = java.lang.Double.valueOf(Bytes.toString(values.get(Long.valueOf(2))));
						double lon = java.lang.Double.valueOf(Bytes.toString(values.get(Long.valueOf(3))));												
						
						Point2D.Double resPoint = new Point2D.Double(lat,lon);									
						double distance = resPoint.distance(point);

						if (distance <= radius) {
							System.out.println("row=>"+Bytes.toString(r.getRow()) + ";colum=>"+Bytes.toString(col)+";station=>"+id+";distance=>"+distance+";latitude=>"+lat+";long=>"+lon);
							accepted++;
							results.put(id,String.valueOf(distance));
						}else{
							//System.out.println("Discard: row=>"+Bytes.toString(r.getRow()) + ";colum=>"+Bytes.toString(col)+";station=>"+id+";distance=>"+distance+";latitude=>"+lat+";long=>"+lon);
						}
					}
				}
			}
			long eTime = System.currentTimeMillis();
			this.timePhase.add(eTime);
			
			
			// write to csv file
			String outStr = "";
			outStr += "within,"+"scan,"+accepted+","+count+","+row+","+
						(eTime-sTime)+","+match_time+","+this.tableSchema.getSubSpace()+","+radius;				
			
						
			for(int i=0;i<this.timePhase.size();i++){
				outStr += ",";
				outStr += this.timePhase.get(i);
			}
					
			this.writeCSVLog(outStr, 0);
			
			
		} catch (Exception e) {
			e.printStackTrace();
		}finally{
			this.hbase.closeTableHandler();			
			this.closeCSVLog();
		}
		return results;
	}

	@Override
	public void copQueryAvailableKNN(String timestamp, double latitude,
			double longitude, int n) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public TreeMap<Double, String> scanQueryAvailableKNN(String timestamp,
			double latitude, double longitude, int n) {
		// TODO Auto-generated method stub
		return null;
	}
	
	/**
	 * reorganize the keys, the original one is {12=>[(1,3),(3,4)]; 30=>[(1,0),(3,1)]}, 12 is the key from quad tree, 
	 * (1,3) the left top point of the region, (3,4) is the bottom right point of the region
	 * Result: {12-1=>[3,4], 12-3=>[3,4], 30-1=>[0,1], 30-3=>[0,1]} 
	 */
	private Hashtable<String,String[]> reOrganizeKeys(Hashtable<String,XBox[]> ranges){
		Hashtable<String,String[]> result = null;
		if(ranges != null){
			result = new Hashtable<String,String[]>();
			for(String tileId: ranges.keySet()){
				XBox[] rasters = ranges.get(tileId);
				result.put(tileId+"-"+rasters[0].getRow(), new String[]{rasters[0].getColumn(),rasters[1].getColumn()});
				result.put(tileId+"-"+rasters[1].getRow(), new String[]{rasters[0].getColumn(),rasters[1].getColumn()});
			}
		}	
		
		return result;
	}
	
	
	
	
	@Override
	public String copQueryPoint(double latitude, double longitude) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public String scanQueryPoint(double latitude, double longitude) {
		// TODO Auto-generated method stub
		return null;
	}	
	

}