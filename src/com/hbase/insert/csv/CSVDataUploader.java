package com.hbase.insert.csv;

import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;

import org.apache.hadoop.hbase.client.HTable;
import org.apache.hadoop.hbase.client.Put;

import com.hbase.service.HBaseUtil;
import com.util.XCSVFormat;
import com.util.XConstants;
import com.util.XTableSchema;
import com.util.hybrid.XHybridIndex;
import com.util.quadtree.trie.XQuadTree;
import com.util.raster.X3DGrid;
import com.util.raster.XBox;

public class CSVDataUploader {

	HBaseUtil hbase = null;

	XTableSchema tableSchema = null;
	XCSVFormat csvFormat = null;
	XQuadTree quadTree = null;
	X3DGrid raster = null;
	XHybridIndex hybrid = null;

	/**
	 * @throws IOException
	 */
	public CSVDataUploader(String csv_desc_file, String schema_desc_file,String hbaseConf) {

		this.getConfiguration(csv_desc_file, schema_desc_file);		
		
		try {
			this.setHBase(hbaseConf);
		} catch (Exception e) {
			e.printStackTrace();
		}

	}	
	
	/**
	 * There are five parameters required to configure
	 * @param args
	 */
	public static void main(String[] args) {
		
		if(args.length < 4){
			System.out.println("input parameter should be " +
							"csv_desc_file: in tempate folder"+
							"schema_desc_file: in tempate folder"+
							"hbase_conf_file: in conf folder"+
							"input folder of data"+
							"number of puts uploaded at a time <optional>");
			return;
		}
		
		String csv_desc_file = args[0];
		String schema_desc_file = args[1];
		String hbaseConf = args[2];
		String input_dir = args[3];
		int batchNum = 1000;
		if(args.length == 5)
			batchNum = Integer.valueOf(args[4]);
		
		XTableSchema tableSchema = new XTableSchema(schema_desc_file);
		
		CSVDataUploader uploader = null;
		
		// this is for hybrid indexing
		if(tableSchema.getIndexing() == XConstants.INDEX_HYBRID){
			uploader = new Uploader4HybridIndex(csv_desc_file,schema_desc_file,hbaseConf);
			
		}else{ // this is for both raster and quadtree			
			uploader = new CSVDataUploader(csv_desc_file,schema_desc_file,hbaseConf);
		}
		
		System.out.println("***** Start to upload the data*********");
		try{
			uploader.upload(input_dir, batchNum);	
		}catch(Exception e){
			e.printStackTrace();
		}
		
		System.out.println("***** Finish uploading all files**********");
		
	}	
	

	/**
	 * workflow of this function: 1 read the csv data, and put into hbase, then close hbase
	 * @param input_dir
	 * @param batchNum
	 * @throws Exception
	 */
	public void upload(String input_dir, int batchNum) throws Exception{
		// read the file,
		File dir = new File(input_dir);
		if (!dir.isDirectory()){
			throw new Exception("there is no this input directory!");			
		}
			

		String[] fileNames = dir.list();

		long start = System.currentTimeMillis();

		BufferedReader in = null;
		int file_num = 0;
		int num_of_row = 0;
				
		String family = this.tableSchema.getFamilyName();
		// get lantitude and longitude index, id index		
		this.locateKeyIndicator();

		ArrayList<Put> putList = new ArrayList<Put>();
	
		for (String fileName : fileNames) {

			if (!fileName.endsWith(".csv"))
				continue;
			long fstart = System.currentTimeMillis();
			long beginFlush = -1;
			int totalRow = 0;
			int flushCount = 0;
			long totalFlush = 0;
			long indicateTime = 0;
			long totalIndicator = 0;
			try {

				in = new BufferedReader(new FileReader(input_dir + "/"
						+ fileName));
				// read the file
				String line = in.readLine();
				
				while (line != null) {
					line = line.trim();
					if (line.length() == 0) {
						continue;
					}
					String[] values = line.split(",");
					// for the debug time
					if(putList.size() == 0){
						beginFlush = System.currentTimeMillis();
						indicateTime = 0;
					}
					
					// generate cell indicator and cell value
					long s = System.currentTimeMillis();					
					String[] cell_indicator = this.getCellIndicator(values[lan_index],values[long_index],values[id_index]);
					indicateTime+=(System.currentTimeMillis()-s);
					// for quad Tree indexing, there is no need to store id in a cell because id is the qualifer
					ArrayList<String> columnsFilter = null;
					if(this.quadTree != null){
						columnsFilter = new ArrayList<String>();
						columnsFilter.add("id");
					}
					
					String cell_value = this.getCellValue(values,columnsFilter);					

					// insert it into hbase
					Put put = new Put(cell_indicator[0].getBytes());
					put.add(family.getBytes(), cell_indicator[1].getBytes(), Long.valueOf(cell_indicator[2]), 
								cell_value.getBytes());
										
					putList.add(put);
					num_of_row++;
					if (putList.size() == batchNum) {						
						long flushTime = hbase.flushBufferedRow(putList);
						totalFlush += flushTime;
						totalRow += batchNum;
						totalIndicator+=indicateTime;
						System.out.println("flushNo=>"+(flushCount++)+";insertedRow=>"+
								totalRow+";flushTime=>"+flushTime+
								";lastFlush=>"+(System.currentTimeMillis() - beginFlush)+
								";IndicatorTime=>"+indicateTime+
								";totalTime(s)=>"+(System.currentTimeMillis() - fstart)/1000+
								";totalFlush=>"+totalFlush+
								";TotalIndicator=>"+totalIndicator);
						putList.clear();
					}
					line = in.readLine();
				}

				// for the last lines
				if (putList.size() > 0) {
					hbase.flushBufferedRow(putList);
					totalRow += putList.size();
					System.out.println("inserted row=> "+totalRow);
					putList.clear();
				}
				file_num++;
				in.close();
				System.out.println("file_name=>" + fileName + ";file_num=>"
						+ file_num + ";exe_time(s)=>"
						+ (System.currentTimeMillis() - fstart)/(1000)
						+ ";file_total_number=>" + totalRow);

			} catch (Exception e) {
				e.printStackTrace();
			} finally {
				try {
					if (in != null)
						in.close();
				} catch (Exception e) {
					e.printStackTrace();
				}			
			}
		} // end of files list
		
		this.hbase.closeTableHandler();
		System.out.println("file_num=>"+file_num+";exe_time(s)=>"
				+ (System.currentTimeMillis() - start)/(1000) + ";total_number=>"
				+ num_of_row);		

	}	
	
	
	private void getConfiguration(String input_desc_file,
			String schema_desc_file) {
		this.tableSchema = new XTableSchema(schema_desc_file);
		this.csvFormat = new XCSVFormat(input_desc_file);
		this.setIndexing();
	}

	private void setHBase(String hbaseConfPath) throws IOException {
		hbase = new HBaseUtil(hbaseConfPath);
		HTable tableHandler = hbase.getTableHandler(this.tableSchema.getTableName());
		
		String bufferSize = hbase.getHBaseConfig().get("hbase.client.write.buffer");		 
		if(bufferSize != null){
			tableHandler.setWriteBufferSize(Long.valueOf(bufferSize));
		}else{
			System.out.println("Default buffer size: "+tableHandler.getWriteBufferSize());
		}		
	}	

	/**
	 * prepare the indexing
	 * 
	 * @param indexing
	 * @param encoding
	 */
	private void setIndexing() {

		Rectangle2D.Double space = this.tableSchema.getEntireSpace();
		double min_size_of_subspace = this.tableSchema.getSubSpace();
		Point2D.Double offset = this.tableSchema.getOffset();
		int indexing = this.tableSchema.getIndexing();
		int encoding = this.tableSchema.getEncoding();
		
		if (indexing == XConstants.INDEX_QUAD_TREE) {
			if (encoding == XConstants.ENCODING_BINARY) {

				this.quadTree = new XQuadTree(space, min_size_of_subspace,offset);
				this.quadTree.buildTree(XConstants.ENCODING_BINARY);

			} else if (encoding == XConstants.ENCODING_DECIMAL) {
				this.quadTree = new XQuadTree(space, min_size_of_subspace,offset);
				this.quadTree.buildTree(XConstants.ENCODING_DECIMAL);
			}
		} else if (indexing == XConstants.INDEX_RASTER) {
			this.raster = new X3DGrid(space, min_size_of_subspace,offset);	
			
		} else if (indexing == XConstants.INDEX_HYBRID){			
			System.out.print("initialize hybrid");
			this.hybrid = new XHybridIndex(space,this.tableSchema.getTileSize(),offset,min_size_of_subspace);
			this.hybrid.buildZone(encoding);
			
		}else{
			System.out.println("Indexing parameter is error!");
		}
	}


	/**
	 * These three variable are to locate the index of langitude, 
	 * longitude, and id which are used to calcuate the rowkey,column id, and version
	 */
	protected int lan_index = -1;
	protected int long_index = -1;
	protected int id_index = -1; 
	
	protected void locateKeyIndicator(){
		System.out.println("start to locate the key indicator");
		String[] columns = this.csvFormat.getColumns();
		for(int i=0;i<columns.length;i++){
			if(columns[i].equals("lat")){
				lan_index = i;					
			}
			if(columns[i].equals("long")){
				long_index = i;				
			}
			if(columns[i].equals("id")){
				id_index = i;				
			}
			if(lan_index > 0 && long_index > 0 && id_index > 0){
				break;
			}
		}
		
		System.out.println("id=>"+id_index+";lan=>"+lan_index+";long=>"+long_index);
	}
	

	/**
	 * Get cell indicator for the data point, based indexing, encdding
	 *  
	 * @return string array including: row key, column key and version
	 */
	protected String[] getCellIndicator(String lan, String longitude,String id){		
		if(lan == null || longitude == null)
			return null;

		double d_lan = Double.valueOf(lan).doubleValue()>100? 99.9999:Double.valueOf(lan).doubleValue();
		double d_longitude = Double.valueOf(longitude).doubleValue()>100? 99.99:Double.valueOf(longitude).doubleValue();

		String indicator[] = new String[3];		
		if(this.quadTree != null){			
			
			XQuadTree node = quadTree.locate(d_lan,d_longitude);
			indicator[0] = node.getIndex();
			indicator[1] = id;
			indicator[2] = "1";
		}
		if(this.raster != null){
			XBox box = raster.locate(d_lan,d_longitude);
			indicator[0] = box.getRow();
			indicator[1] = box.getColumn();
			indicator[2] = String.valueOf(box.getObjectCount());
		}
					
		return indicator;
				
	}
	/**
	 * compose the metadata into a string splitting with ';', e.g. id=1;name=s1;terminal=s1;
	 * @param values
	 * @return
	 */
	protected String getCellValue(String values[],ArrayList<String> filter){	
		String metaData = null;
		try{
			String[] columns = this.csvFormat.getColumns();
			if(columns.length != values.length)
			{
				System.out.println("the value and colum does not match"+values[0]);
				return null;
			}
			
			metaData = this.csvFormat.pairToString(values,filter);
			
		}catch(Exception e){
			e.printStackTrace();
		}
		
		return metaData;
				
	}	
	

}
