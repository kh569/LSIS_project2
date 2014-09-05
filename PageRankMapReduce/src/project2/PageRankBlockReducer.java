package project2;

import java.io.IOException;
import java.util.*;

import org.apache.hadoop.fs.Path;
import org.apache.hadoop.conf.*;
import org.apache.hadoop.io.*;
import org.apache.hadoop.mapreduce.*;
import org.apache.hadoop.mapreduce.Reducer.Context;
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat;
import org.apache.hadoop.mapreduce.lib.output.FileOutputFormat;
import org.apache.hadoop.util.*;


public class PageRankBlockReducer extends Reducer<Text, Text, Text, Text> {

	private HashMap<String, Float> newPR = new HashMap<String, Float>();
	private HashMap<String, ArrayList<String>> BE = new HashMap<String, ArrayList<String>>();
	private HashMap<String, Float> BC = new HashMap<String, Float>();
	private HashMap<String, NodeData> nodeDataMap = new HashMap<String, NodeData>();
	private ArrayList<String> vList = new ArrayList<String>();
	private Float dampingFactor = (float) 0.85;
	private Float randomJumpFactor = (1 - dampingFactor) / PageRankBlock.totalNodes;
	private int maxIterations = 5;
	private Float threshold = 0.001f;
	
	protected void reduce(Text key, Iterable<Text> values, Context context)
			throws IOException, InterruptedException {
		
		Iterator<Text> itr = values.iterator();
		Text input = new Text();
		String[] inputTokens = null;
		
		// initialize/reset all variables
		Float pageRankOld = (float) 0.0;
		Float residualError = (float) 0.0;
		
		String output = "";
		Integer maxNode = 0;
		
		ArrayList<String> temp = new ArrayList<String>();
		float tempBC = 0.0f;
		vList.clear();
		newPR.clear();
		BE.clear();
		BC.clear();
		nodeDataMap.clear();	
		
		while (itr.hasNext()) {
			input = itr.next();
			inputTokens = input.toString().split(" ");			
			// if first element is PR, it is the node ID, previous pagerank and outgoing edgelist for this node
			if (inputTokens[0].equals("PR")) {
				String nodeID = inputTokens[1];
				pageRankOld = Float.parseFloat(inputTokens[2]);
				newPR.put(nodeID, pageRankOld);
				NodeData node = new NodeData();
				node.setNodeID(nodeID);
				node.setPageRank(pageRankOld);
				if (inputTokens.length == 4) {
					node.setEdgeList(inputTokens[3]);
					node.setDegrees(inputTokens[3].split(",").length);
				}
				vList.add(nodeID);
				nodeDataMap.put(nodeID, node);
				// keep track of the max nodeID for this block
				if (Integer.parseInt(nodeID) > maxNode) {
					maxNode = Integer.parseInt(nodeID);
				}
				
			// if BE, it is an in-block edge
			} else if (inputTokens[0].equals("BE")) {			
				
				if (BE.containsKey(inputTokens[2])) {
					//Initialize BC for this v
					temp = BE.get(inputTokens[2]);
				} else {
					temp = new ArrayList<String>();
				}
				temp.add(inputTokens[1]);
				BE.put(inputTokens[2], temp);
				
			// if BC, it is an incoming node from outside of the block
			} else if (inputTokens[0].equals("BC")) {
				if (BC.containsKey(inputTokens[2])) {
					//Initialize BC for this v
					tempBC = BC.get(inputTokens[2]);
				} else {
					tempBC = 0.0f;
				}
				tempBC += Float.parseFloat(inputTokens[3]);
				BC.put(inputTokens[2], tempBC);
			}		
		}
		
		int i = 0;
		do {
			i++;
			residualError = IterateBlockOnce();
			//System.out.println("Block " + key + " pass " + i + " resError:" + residualError);
		} while (i < maxIterations && residualError > threshold);

				
		// compute the ultimate residual error for each node in this block
		residualError = 0.0f;
		for (String v : vList) {
			NodeData node = nodeDataMap.get(v);
			residualError += Math.abs(node.getPageRank() - newPR.get(v)) / newPR.get(v);
		}
		residualError = residualError / vList.size();
		//System.out.println("Block " + key + " overall resError for iteration: " + residualError);
		
		// add the residual error to the counter that is tracking the overall sum (must be expressed as a long value)
		long residualAsLong = (long) Math.floor(residualError * PageRankBlock.precision);
		context.getCounter(PageRankBlock.ProjectCounters.RESIDUAL_ERROR).increment(residualAsLong);
		
		// output should be 
		//	key:nodeID (for this node)
		//	value:<pageRankNew> <degrees> <comma-separated outgoing edgeList>
		for (String v : vList) {
			NodeData node = nodeDataMap.get(v);
			output = newPR.get(v) + " " + node.getDegrees() + " " + node.getEdgeList();
			Text outputText = new Text(output);
			Text outputKey = new Text(v);
			context.write(outputKey, outputText);
			if (v.equals(maxNode.toString())) {
				System.out.println("Block:" + key + " node:" + v + " pageRank:" + newPR.get(v));
			}
		}
			
		cleanup(context);
	}
	

	// v is all nodes within this block B
	// u is all nodes pointing to this set of v
	// some u are inside the block as well, those are in BE
	// some u are outside the block, those are in BC
	// BE = the Edges from Nodes in Block B
    // BC = the Boundary Conditions
	// NPR[v] = Next PageRank value of Node v
	protected float IterateBlockOnce() {
		// used to iterate through the BE list of edges
		ArrayList<String> uList = new ArrayList<String>();
		// npr = current PageRank value of Node v
		float npr = 0.0f;
		// r = sum of PR[u]/deg[u] for boundary nodes pointing to v
		float r = 0.0f;
		// resErr = the avg residual error for this iteration
		float resErr = 0.0f;
		
		for (String v : vList) {
			npr = 0.0f;
			float prevPR = newPR.get(v);

			// calculate newPR using PR data from any BE nodes for this node
			if (BE.containsKey(v)) {
				uList = BE.get(v);
				for (String u : uList) {
					// npr += PR[u] / deg(u);
					NodeData uNode = nodeDataMap.get(u);
					npr += (newPR.get(u) / uNode.getDegrees());
				}
			}
			
			// add on any PR from nodes outside the block (BC)
			if (BC.containsKey(v)) {
				r = BC.get(v);
				npr += r;
			}
	
	        //NPR[v] = d*NPR[v] + (1-d)/N;
			npr = (dampingFactor * npr) + randomJumpFactor;
			// update the global newPR map
			newPR.put(v, npr);
			// track the sum of the residual errors
			resErr += Math.abs(prevPR - npr) / npr;
		}
		// calculate the average residual error and return it
		resErr = resErr / vList.size();
		return resErr;
	}

}

