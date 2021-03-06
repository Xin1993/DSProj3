package mapred;

//
import java.io.*;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.net.Socket;
import java.net.UnknownHostException;
//import java.net.Socket;
//import java.net.UnknownHostException;
//import java.io.FileReader;
import java.util.*;

import node.FileInfo;
import node.SlaveMain;
//import java.util.HashMap;
//
import communication.Message;
import communication.Message.MSG_TYPE;
import communication.ReducerDoneMsg;
import config.ParseConfig;
import debug.Printer;
import dfs.FileTransfer;
import io.FixValue;
import io.Text;
import io.Writable;

//
public class PerformReduce extends Thread {
	ArrayList<String> mapperOutputs;
	ArrayList<String> outputs;
	ArrayList<KeyValuePair> pairs = new ArrayList<KeyValuePair>();
	Task taskInfo;
	public class KeyValuePair implements Comparable<KeyValuePair> {

		public Text key;
		public FixValue value;

		public KeyValuePair(Text k, FixValue v) {
			this.key = k;
			this.value = v;
		}

		@Override
		public int compareTo(KeyValuePair kv1) {
			return this.key.hashCode() - kv1.key.hashCode();
		}
	}

	public PerformReduce(Task taskInfo){
		this.taskInfo = taskInfo;
		this.mapperOutputs = new ArrayList<String>();
		File folder = new File("./");
		File[] listOfFiles = folder.listFiles();
		System.out.println("list of files"+listOfFiles.length);
		for (File file : listOfFiles) {
//		    if (file.isFile()) {
		    	
		    	System.out.println("file in src name is:" +file.getAbsoluteFile());
		    	String[] parts = file.getName().split("_");
		    	if(parts[0].equals(taskInfo.getJobId()+"")&&parts[2].equals("Reduce")){
//		    	if (file.getName().equals("0_0_Reduce") || file.getName().equals("0_0_MapResult0") ){
//		    		if (file.getName().equals("0_0_Reduce") )
//		    			System.out.println("0_0_Reduce is right");
		    		//add file name into mapper outputs
		    		mapperOutputs.add(file.getName());
		    		System.out.println("perform reduce Right file!");
		    	}else {
		    		System.out.println("perform reduce Wrong file!");
		    	}
		    }
//		}
		System.out.println("perform reduce "+mapperOutputs.size());
	}
	
	public void run() {

		try {
			System.out.println("starting read reduce file!!");
			// read all the mappers output file into one file
			for (String mapperOutputFile : mapperOutputs) {
				// FileReader fr;
				System.out.println("reading... " + mapperOutputFile);
				BufferedReader br = new BufferedReader(new FileReader(mapperOutputFile));
				String line;
				int count=0;
				while ((line = br.readLine()) != null) {
					/*test*/
					if(count < 5){
						System.out.println("cur line is " + line);
						count++;
					}
					KeyValuePair kvp = parseLine(line);
					pairs.add(kvp);
				}
			}

			// sort the pairs
			sortKeyValuePairs(pairs);

			// merge the pairs
			HashMap<Text, ArrayList<FixValue>> reduceResult = mergeKeyValuePairs(pairs);

			// performa reducer()
			performReducer(reduceResult);

			System.out.println("here the reduceResult size: "+reduceResult.size());
			// send the result back to master
//			sendResultToMaster(""+taskInfo.getJobId());
			// send file name to master instead actual files
			sendResultFileNameToMaster();

		} catch (Exception e) {
			e.printStackTrace();
		} 
	}

	private void sendResultFileNameToMaster() throws IOException, Exception {
		String fileName = taskInfo.getJobId() +"_"+ taskInfo.getTaskId() + "_reduceResult0";
		FileInfo fileInfo = new FileInfo();
		fileInfo.fileName = fileName;
		fileInfo.slaveInfo = taskInfo.getReduceSlave();
		fileInfo.taskInfo = taskInfo;
		Message msg = new Message(MSG_TYPE.REDUCER_DONE, fileInfo);
		Socket socket = new Socket(SlaveMain.conf.MasterIP,SlaveMain.conf.MasterMainPort);
		msg.send(socket);
	}

	private void sendResultToMaster(String uploadFile) throws Exception {
		Socket soc = new Socket(SlaveMain.conf.MasterIP,SlaveMain.conf.MasterMainPort);
//		ArrayList<String> tempList = new ArrayList<String>();
//		
//		File folder = new File("/");
//		File[] listOfFiles = folder.listFiles();
//		for (File file : listOfFiles) {
//		    if (file.isFile()) {
//		    	System.out.println("file in src name is:" +file.getName());
//		    	String[] parts = file.getName().split("_");
//		    	if(parts[0].equals(taskInfo.getJobId())&&parts[2].equals("reduceResult")){
//		    		//add file name into mapper outputs
//		    		tempList.add(file.getName());
//		    		System.out.println("Right file!");
//		    	}else {
//		    		System.out.println("Wrong file!");
//		    	}
//		    }
//		}
		
		
		
		String fileName = taskInfo.getJobId() +"_"+ taskInfo.getTaskId() + "_reduceResult0";
		
		ReducerDoneMsg replyContent = new ReducerDoneMsg(taskInfo, null);
		replyContent.fileName = fileName;
		
		Message msg = new Message(MSG_TYPE.REDUCER_DONE, replyContent);
		msg.send(soc);
		/*for test!!!!!*/
		Random random = new Random();
		int next = random.nextInt(3);
		Thread.sleep(next * 2000);
		/*for test only!!!!*/
		
		new FileTransfer.Upload(fileName.substring(0,fileName.length()), soc).start();
		
		
		
	}

	private void performReducer(HashMap<Text, ArrayList<FixValue>> reduceResult) {
		// prepare args for reducer
		Class reduceClass;
		try {
			reduceClass = Class.forName("example." + taskInfo.getReducerClass());
			Constructor<?> objConstructor = reduceClass.getConstructor();
			Reducer reducer = (Reducer) objConstructor.newInstance();
			
			Context context = new Context(1,((Integer)taskInfo.jobId).toString()+"_"+taskInfo.getTaskId()+"_reduceResult");
			
			
			for(Text key : reduceResult.keySet()) {
				reducer.reduce(key, reduceResult.get(key), context);
			}
			//CLOSE!!!!
			context.close();
			System.out.println("perform reduce ends");
			
		} catch (Exception e) {
			System.out.println("internal error from perform reducer");
			e.printStackTrace();
		} 
		


	}

	private HashMap<Text, ArrayList<FixValue>> mergeKeyValuePairs(ArrayList<KeyValuePair> pairs) {
		System.out.println("Merge starts");
		HashMap<Text, ArrayList<FixValue>> reduceResult = new HashMap<Text, ArrayList<FixValue>>();
		for (KeyValuePair kvp : pairs) {
			//System.out.println("key = "+kvp.key + " value = "+kvp.value);
			if (reduceResult.containsKey(kvp.key)) {
				reduceResult.get(kvp.key).add(kvp.value);
			} else {

				reduceResult.put(kvp.key, new ArrayList<FixValue>());
				reduceResult.get(kvp.key).add(kvp.value);
			}
		}
		System.out.println("the size of the reduceResult arraylist: "+reduceResult.size());
		System.out.println("Merge ends");
		/*for test*/
//		Printer.printT(reduceResult);
		return reduceResult;

	}

	private void sortKeyValuePairs(ArrayList<KeyValuePair> pairs) {
		System.out.println("sort starts");
		System.out.println("before sort, pairs size: "+pairs.size());
		Collections.sort(pairs);
		System.out.println("after sort, pairs size: "+pairs.size());
		System.out.println("sort ends");
	}

	private KeyValuePair parseLine(String line) {
		System.out.println(line);
		
		String[] arr = line.split("\\:");
		System.out.println(arr[0]);
		Text key = new Text(arr[0]);
		System.out.println(arr[1]);
		FixValue value = new FixValue(arr[1]);
		KeyValuePair kvp = new KeyValuePair(key, value);
		return kvp;
	}

}
//
//
// HashMap<Writable<?>, Writable<?>> results = new HashMap<Writable<?>,
// Writable<?>>();
// public PerformReduce() {
// // System.out.println();
//
// }
//
//
// public void run(){
// for (int i = 0; i < mapperOutputs.size(); i++) {
// try {
// FileReader fr = new FileReader(mapperOutputs.get(i));
// BufferedReader br = new BufferedReader(fr);
// ArrayList<KeyValuePair> pairs = new ArrayList<KeyValuePair>();
// String line;
//
// while (true) {
// line = br.readLine();
// if (line == null) break;
// else {
// pairs.add(parseRecord(line, i));
//
// //add the key value pair into the final result
//
// }
// }
// br.close();
// fr.close();
//
//
// new File(mapperOutputs.get(i)).delete();
//
// //write the pairs to a file
//
// } catch (FileNotFoundException e) {
// // TODO Auto-generated catch block
// e.printStackTrace();
// } catch (IOException e) {
// // TODO Auto-generated catch block
// e.printStackTrace();
// }
//
// }
//
// //merge all the result files
// String reducerResult = null;
// reducerResult = merge(outputs);
//
// //send the reducer result msg to master
// sendResult();
// }
//
// private void sendResult() {
// try {
// Socket socket = new Socket(ParseConfig.MasterIP, ParseConfig.MasterMainPort);
// //what is the content of the msg???
// new Message(Message.MSG_TYPE.REDUCER_DONE, null).send(socket);;
//
// Message.receive(socket);
// socket.close();
//
// } catch (UnknownHostException e) {
// // TODO Auto-generated catch block
// e.printStackTrace();
// } catch (IOException e) {
// // TODO Auto-generated catch block
// e.printStackTrace();
// } catch (Exception e) {
// // TODO Auto-generated catch block
// e.printStackTrace();
// }
//
//
// }
//
// private String merge(ArrayList<String> outputs) {
// return null;
// }
//
// private KeyValuePair parseRecord(String line, int i) {
// // TODO Auto-generated method stub
// return null;
// }
//
// }
