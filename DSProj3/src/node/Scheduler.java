package node;

import io.FixValue;
import io.Text;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import mapred.Job;
import mapred.Task;
import communication.Message;
import communication.Message.MSG_TYPE;
import communication.ReducerDoneMsg;
import communication.WriteFileMsg;
import config.ParseConfig;
import dfs.FileTransfer;

public class Scheduler extends Thread{
	//slave pool
	public static ConcurrentHashMap<Integer,SlaveInfo> slavePool = new ConcurrentHashMap<Integer,SlaveInfo>();
	//job pool
	public static ConcurrentHashMap<Integer, SlaveInfo> failPool = new ConcurrentHashMap<Integer, SlaveInfo>();

	public static ConcurrentHashMap<Integer, Job> jobPool = new ConcurrentHashMap<Integer, Job>();
	//file layout record key: slaveInfo value : fileName with blk id
	public static ConcurrentHashMap<String,ArrayList<SlaveInfo>> fileLayout = new ConcurrentHashMap<String,ArrayList<SlaveInfo>>();
	
	public static ConcurrentHashMap<Job, ArrayList<Task>> jobToMapper = new ConcurrentHashMap<Job, ArrayList<Task>>();
	
	public static ConcurrentHashMap<Job, ArrayList<Task>> jobToReducer = new ConcurrentHashMap<Job, ArrayList<Task>>();
    
	public static ConcurrentHashMap<Task, SlaveInfo> TaskToSlave = new ConcurrentHashMap<Task, SlaveInfo>();
	public static ConcurrentHashMap<SlaveInfo, ArrayList<Task>> SlaveToTask = new ConcurrentHashMap<SlaveInfo, ArrayList<Task>>();
	
	public static ConcurrentHashMap<Job,ArrayList<FileInfo>> resFileTable = new ConcurrentHashMap<Job, ArrayList<FileInfo>>();
	public static int slaveId = 0;
	public static int curJobId = 0;
	ServerSocket listener;
	public Scheduler(int port) throws IOException{
		listener = new ServerSocket(port);
	}
	
	public void run() {
		
		while(true) {
			Socket socket = null;
			Message msg = null;
			ParseConfig conf = null;
			try {
				socket = listener.accept();
				conf = MasterMain.conf;
				msg = Message.receive(socket);
				
				System.out.println("Receives a "+msg.getType()+" messge: " + msg.getContent().toString());
				
			} catch (Exception e) {
				e.printStackTrace();
				continue;
			}
			
			switch (msg.getType()) {
			case REG_NEW_SLAVE:
				regNewSlaveHandler(socket);
				break;
			case KEEP_ALIVE:
				
				break;
			case FILE_PUT_REQ_TO_MASTER:
				
				WriteFileMsg writeFileMsg = (WriteFileMsg) msg.getContent(); 
				System.out.println("Write File: " + writeFileMsg.fileBaseName +" blk:" + writeFileMsg.fileBlk);
				ArrayList<SlaveInfo> slaveList = null; //slaveList to be sent to client
				for(int rep = 0; rep <= conf.Replica; rep++) {
					if(slaveList==null) {
						slaveList = fileLayoutGenerate(slavePool, writeFileMsg,false);
					}else {
						slaveList.addAll(fileLayoutGenerate(slavePool,writeFileMsg,true));
					}
					
				}

				filePutReqToMasterHandler(socket,msg,slaveList);
				break;
				
			case NEW_JOB:
				System.out.println("submitting a job from the master to mapper nodes!");
				submitMapperJob((Job) msg.getContent(), socket);
				resFileTable.put((Job) msg.getContent(), new ArrayList<FileInfo>());
				break;
			
			case MAPPER_DONE:
				try {
					Task task = (Task) msg.getContent();
					Job job = jobPool.get(task.getJobId());
					job.finishedMapperTasks++;
					//if all the mapper tasks sucess, assign the reducer tasks
					if (job.finishedMapperTasks == job.getMapperTaskSplits()){
						System.out.println("All the mapper task of "+job.getJobName()+" are done!");
						submitReduceJob(job);
					}else 
					System.out.println("mappers of this job haven't been all done");
				} catch (Exception e) {
					e.printStackTrace();
				}
				break;
			
			case REDUCER_DONE:
			{
				reducerDoneHandler(msg,socket);
				break;
			}
			case GETFILE:
				getFileHandler(msg,socket);
				break;
			default:
				break;
			}

		}
	}

	private void getFileHandler(Message msg, Socket socket) {
		
		try {
			FileInfo info = (FileInfo) msg.getContent();
			SlaveInfo slave = slavePool.get(info.slaveInfo.slaveId);
			if(slave == null) {
				Message reply = new Message(null,"No Such files");
				reply.send(socket);
				socket.close();
			}else {
				Message reply = new Message(null,slave);
				reply.send(socket);
				socket.close();
			}
			
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	private void reducerDoneHandler(Message msg,Socket socket) {
		
		
//		mergeFile(curJob, reducerDoneMsg.ReduceResultMap);
		
		
		//STRATEGY 1 DOWNLOAD ACTUAL FILES
//		ReducerDoneMsg reducerDoneMsg = (ReducerDoneMsg) msg.getContent();
//		Job curJob = jobPool.get(reducerDoneMsg.task.getJobId());
//		curJob.finishedReducerTasks++;
//		ReducerDoneMsg content = (ReducerDoneMsg) msg.getContent();
		
//		new FileTransfer.Download(content.fileName, socket, MasterMain.conf.ChunkSize).start();
		
		//STRATEGY 2 RECEIVE FILES NAMES
		FileInfo fileInfo = (FileInfo) msg.getContent();
		Job curJob = jobPool.get(fileInfo.taskInfo.getJobId());
		curJob.finishedReducerTasks++;
		
		resFileTable.get(curJob).add(fileInfo);
		
		if (curJob.finishedReducerTasks == curJob.getReducerTaskSplits()) {
			
			
			
			
			//inform the client
			try {
				//STRATEGY 2 SEND FILESINFO TO CLIENT
				System.out.println("Job done successfully! Transfer the job result to the client");
				
				System.out.println("All the reducer task of "+curJob.getJobName()+" are done!");
				
				Socket reduceRes = new Socket(curJob.getAddress(),MasterMain.conf.ClientMainPort);
				
				//message content is ArrayList<FileInfo>
				Message reduceResMsg = new Message(MSG_TYPE.JOB_COMP, resFileTable.get(curJob));
				
				reduceResMsg.send(reduceRes);
				
				//read all reduce result files and send to client!!
//				ArrayList<String> jobResultFiles = new ArrayList<String>();
//				
//				File folder = new File("./");
//				File[] listOfFiles = folder.listFiles();
//				System.out.println("list of files"+listOfFiles.length);
//				for (File file : listOfFiles) {
////				    if (file.isFile()) {
//				    	
//				    	System.out.println("file in src name is:" +file.getName());
//				    	String[] parts = file.getName().split("_");
//				    	if(parts.length >= 3 ){
//				    		System.out.println("parts "+parts[0] + " " + parts[1] + " " + parts[2]);
//					    	System.out.println("targs "+reducerDoneMsg.task.getJobId() + " " + parts[1] + " " + "reduceResult0");
//				    	}
//				    	
//				    	if(parts[0].equals(reducerDoneMsg.task.getJobId()+"")&&parts[2].substring(0,12).equals("reduceResult")){
//				    		
////				    	if (file.getName().equals("0_0_Reduce") || file.getName().equals("0_0_MapResult0") ){
////				    		if (file.getName().equals("0_0_Reduce") )
////				    			System.out.println("0_0_Reduce is right");
//				    		//add file name into mapper outputs
//				    		jobResultFiles.add(file.getName());
//				    		System.out.println("perform reduce Right file!");
//				    	}else {
//				    		System.out.println("perform reduce Wrong file!");
//				    	}
//				    }
////				}
//				
//				
//				
//
//				
////				new Message(Message.MSG_TYPE.JOB_COMP, jobResultFiles).send(socket1);
//				System.out.println(curJob.getAddress() + "  " + MasterMain.conf.ClientMainPort);
//				Socket resultSoc = new Socket(curJob.getAddress(),MasterMain.conf.ClientMainPort);
////				Socket resultSoc = new Socket("128.237.184.172",15641);
//				new Message(Message.MSG_TYPE.JOB_COMP, jobResultFiles).send(resultSoc);
//				
//				
//				//send each result files to client
//				for(String str : jobResultFiles){
//					
//					/*for test!!!!!*/
//					Random random = new Random();
//					int next = random.nextInt(3);
//
//					Thread.sleep(next * 2000);
//					
//					System.out.println("filename : " + str);
//					new FileTransfer.Upload("./"+str, resultSoc).start();
//				}
//				
//				
				
				
			} catch (Exception e) {
				System.out.println("reducerDoneHandler fails!");
				e.printStackTrace();
			} 
			
		}
		
	}

	private void mergeFile(Job job, HashMap<Text, FixValue> reduceResultMap) {
		job.reduceOutputMap.putAll(reduceResultMap);
		
	}

	private void submitMapperJob(Job job, Socket socket) {
		job.setAddress(socket.getInetAddress());
		job.setPort(socket.getPort());
		System.out.println("Start the map job!");
		System.out.println("Job Mapper class is " + job.getMapperClass());
		job.setJobId();
		
		jobPool.put(job.getJobId(), job);
		
		
		setReduceList(job);
		
		//find files on what slaves
		HashMap<String, SlaveInfo> fileToSlave = new HashMap<String, SlaveInfo>(); 
		String baseFileName = job.getInputFileName();
		int length = baseFileName.length();
		for(String fileName : fileLayout.keySet()){
			System.out.println("file name from job"+baseFileName);
			if(fileName.substring(0, length).equals(baseFileName)){
				
				System.out.println("file name from dfs"+fileName);
				
				fileToSlave.put(fileName,fileLayout.get(fileName).get(0));
			}
		}
		
		/*
		 * for test
		 */
		for(String file : fileToSlave.keySet()){
			System.out.println(file + " is on " + fileToSlave.get(file).slaveId);
		}
		
	
		//assign the tasks to different slaves
		//send file name to each slaves via socket
		jobToMapper.put(job, new ArrayList<Task>());
		
		for(String file : fileToSlave.keySet()){
			SlaveInfo curSlave = fileToSlave.get(file);
			try {
				//build socket
				Socket soc =  new Socket(curSlave.address.getHostName(),MasterMain.conf.SlaveMainPort);
				//generate a new task
				
				Task task = new Task();
				task.setTaskId(job.curTaskId++);
				task.setInputFileName(file);
				task.setMapperClass(job.getMapperClass());
				System.out.println("mapper class in submit is " + job.getMapperClass());
				task.setJobName(job.getJobName());
				task.setJobId(job.getJobId());
				task.setReduceLists(job.getReduceLists());
				task.setInputFileName(file);
				task.setAddress(job.getAddress());
				jobToMapper.get(job).add(task);
				TaskToSlave.put(task, curSlave);
				if(SlaveToTask.contains(curSlave)){
					SlaveToTask.get(curSlave).add(task);
				}else {
					SlaveToTask.put(curSlave, new ArrayList<Task>());
					SlaveToTask.get(curSlave).add(task);
				}
				
				//send the new task to the slave
				Message taskMsg = new Message(Message.MSG_TYPE.NEW_MAPPER, task);
				
				taskMsg.send(soc);
				System.out.println("");
				soc.close();
				
				
			} catch (Exception e) {
				e.printStackTrace();
			} 
		}
		job.setMapperTaskSplits(fileToSlave.keySet().size());
	}

	
	
	private void setReduceList(Job job) {
//		while(job.getReduceLists().size() < job.getReducerTaskSplits()){
//			if(slavePool.contains(i)){
//				job.getReduceLists().add(slavePool.get(i));
//				i++;
//			}else {
//				i++;
//				if(i>1000){
//				System.out.println("Sorry...We hard code this...cause we assume there won't be more than 1000 nodes ...");
//				}
//			}
//			
//		}
		
		for(int i = 0 ; i < job.getReducerTaskSplits(); i++) {
			job.getReduceLists().add(slavePool.get(i));
		}
		
	}

	private void submitReduceJob(Job job) {
		System.out.println("Start the reduce job!");
		
		for (int i = 0; i < job.getReduceLists().size(); i++) {
			SlaveInfo curSlave = job.getReduceLists().get(i);
			//new a task
			Task task = new Task();
			task.setJobId(job.getJobId());
			task.setReducerClass(job.getReducerClass());
			task.setInputFileName(((Integer)job.getJobId()).toString());
			task.setOutputFileName(job.getOutputFileName());
			task.setTaskId(job.curTaskId++);
			task.setAddress(job.getAddress());
			task.setJobName(job.getJobName());
			//set cur slave to be reducer. we can get this info later after reducer done
			task.setReduceSlave(curSlave);
			TaskToSlave.put(task, curSlave);
			if(SlaveToTask.contains(curSlave)){
				SlaveToTask.get(curSlave).add(task);
			}else {
				SlaveToTask.put(curSlave, new ArrayList<Task>());
				SlaveToTask.get(curSlave).add(task);
			}
			//connect to the slave
			Socket soc;
			try {
				soc = new Socket(curSlave.address.getHostName(),MasterMain.conf.SlaveMainPort);
				
				Message taskMsg = new Message(Message.MSG_TYPE.NEW_REDUCER,task);
				taskMsg.send(soc);
				System.out.println("finish submitReduceJob!");
				
				
				soc.close();
			} catch (Exception e) {
				e.printStackTrace();
			}
					
		}
	}
	
	/*
	 * used for generate file replica policy
	 */
	/*
	private void fileReplicaHandler(WriteFileMsg writeFileMsg, int replica) {
		Random rng = new Random();
		String baseFileName = writeFileMsg.fileBaseName;
		for(int i = 0; i <= writeFileMsg.fileBlk;i++){
			int curRep = 0;
			while(curRep != MasterMain.conf.Replica){
				Integer next = rng.nextInt(slavePool.size());
				if(fileLayout.get(baseFileName + "_blk" +i).contains(slavePool.get(next))) {
					continue;
				}else {
					ArrayList<SlaveInfo> curSlaveList = fileLayout.get(writeFileMsg.fileBaseName + "_blk" + i);
					curSlaveList.add(slavePool.get(next));
					fileLayout.put(writeFileMsg.fileBaseName + "_blk" + i,curSlaveList);
					curRep++;
					break;
				}
			}
			
		}
		
	}
	*/

	/*
	 * Generate file layout policy and update fileLayout table.
	 * Notice, replica is not included
	 */
	private ArrayList<SlaveInfo> fileLayoutGenerate(ConcurrentHashMap<Integer, SlaveInfo> slavePool, WriteFileMsg writeFileMsg, boolean isReplica) {
		if(!isReplica){//when it is first time upload
			Random rng = new Random(); 
//			Set<Integer> idSet = new HashSet<Integer>();
			ArrayList<Integer> idSet = new ArrayList<Integer>();
			//select random slave id for file to be input
			//======
			//trad-off: it maintains load balance, but when there is not enough slave nodes, the function will crush.
			//======
			
			while (idSet.size() <= writeFileMsg.fileBlk)
			{
				System.out.println(slavePool.size());
			    Integer next = rng.nextInt(slavePool.size());
//			    if(slavePool.contains(next)){
			    	 idSet.add(next); //if slave is down, its slave ID will not be used for a while.
			    	 System.out.println("ram next is " + next);
//			    }
			   
			}
			System.out.println("id set size is " + idSet.size());
			ArrayList<SlaveInfo> slaveList = new ArrayList<SlaveInfo>();
			int fileId = 0;
			Iterator it = idSet.iterator();
			while(it.hasNext()) {
				int slaveId = (Integer) it.next();
				
				SlaveInfo slaveInfo = slavePool.get(slaveId);
				slaveList.add(slaveInfo);
				if(fileLayout.keySet().contains(writeFileMsg.fileBaseName + "_blk" + fileId)) {
//					System.out.println("contains is true");
					ArrayList<SlaveInfo> curSlaveList = fileLayout.get(writeFileMsg.fileBaseName + "_blk" + fileId);
					curSlaveList.add(slaveInfo);
					fileLayout.put(writeFileMsg.fileBaseName + "_blk" + fileId,curSlaveList);
				}else {
					ArrayList<SlaveInfo> newSlaveList = new ArrayList<SlaveInfo>();
					newSlaveList.add(slaveInfo);
					fileLayout.put(writeFileMsg.fileBaseName + "_blk" + fileId,newSlaveList);
				}
				fileId++;
			}
			return slaveList;
		} else {//when this is replica
			ArrayList<SlaveInfo> slaveList = new ArrayList<SlaveInfo>();
			for(int i = 0 ; i <= writeFileMsg.fileBlk; i++) {
				//replica policy is put replica into slave id which is 1 bigger than previous copy.
				ArrayList<SlaveInfo> tempList = fileLayout.get(writeFileMsg.fileBaseName + "_blk" + i);
				SlaveInfo last = tempList.get(tempList.size()-1);
				SlaveInfo repSlave = slavePool.get((last.slaveId+1)%slavePool.size());
				slaveList.add(repSlave);
				//update file layout
				tempList.add(repSlave);
				fileLayout.put(writeFileMsg.fileBaseName + "_blk" + i, tempList);
			}
			return slaveList;
		}
		
	}

	/*
	 * reply client which slaves are to be connected to upload file
	 */
	private void filePutReqToMasterHandler(Socket socket, Message msg, ArrayList<SlaveInfo> slaveList) {
		Message reply = new Message(Message.MSG_TYPE.AVAIL_SLAVES, slaveList);
		
		//tell the client which slaves are available
		try {
			
			reply.send(socket);
		} catch (Exception e1) {
			e1.printStackTrace();
		}
		System.out.println("Send the slave list from the master to the client");
	}

	/*
	 * update slavePool when a new slave register on master
	 * @params socket
	 */
	private void regNewSlaveHandler(Socket socket) {
		InetAddress address = socket.getInetAddress();
		SlaveInfo slave = new SlaveInfo(slaveId, address);
		System.out.println("Register slave "+slave.slaveId+ " with the IP: "+ address);
		slavePool.put(slaveId,slave);
		//notice:put first then add slaveid
		slaveId++;
	}


}
