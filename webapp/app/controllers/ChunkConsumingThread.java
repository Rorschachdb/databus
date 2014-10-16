package controllers;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UnsupportedEncodingException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

import models.SecureTable;

import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import play.Play;
import play.mvc.results.Unauthorized;

import com.alvazan.play.NoSql;
import com.alvazan.play.NoSqlPlugin;

import controllers.gui.OurRejectHandler;
import controllers.gui.SocketState;
import controllers.gui.SocketStateCSV;

public class ChunkConsumingThread implements Runnable {
	
	protected static final Logger log = LoggerFactory.getLogger(ChunkConsumingThread.class);

	protected File sharedFile;
	protected String tableName;
	protected FileInputStream in;
	private boolean datacomplete = false;
	private boolean processingcomplete = false;
	private SocketState state;
	private static int numThreads = 10;
	private SecureTable sdiTable;
	private Thread myThread;
	private Thread waitingThread;
	private ThreadPoolExecutor executor;

	
	public ChunkConsumingThread(File sharedFile, String table) {
		this.sharedFile = sharedFile;
		ChunkedCSVUpload.registeredListeners.put(sharedFile.getAbsolutePath(), this);

		this.tableName = table;
		NoSqlPlugin plugin = new NoSqlPlugin();
    	plugin.onApplicationStart();
    	plugin.beforeInvocation();
		try {
			this.sdiTable = SecureTable.findByName(NoSql.em(), tableName);
		}
		catch (Exception e) {
			e.printStackTrace();
			throw new Unauthorized("You do not have access to the table "+tableName+", or it does not exist.");
		}
		String configurednumthreads = Play.configuration.getProperty("socket.upload.num.threads");
		if (StringUtils.isNotBlank(configurednumthreads))
			numThreads = Integer.parseInt(configurednumthreads);
		LinkedBlockingQueue<Runnable> queue = new LinkedBlockingQueue<Runnable>(1000);

		executor = new ThreadPoolExecutor(numThreads, numThreads, 5, TimeUnit.MINUTES, queue, new OurRejectHandler());
		
		state = new SocketStateCSV(NoSql.getEntityManagerFactory(), sdiTable, executor, null);
		try {
			in = new FileInputStream(this.sharedFile);
		}
		catch (Exception e) {
			throw new RuntimeException(e);
		}
	}
	
	public void run() {
		runLoop();
	}
	
	public void runLoop() {
		
		while (!datacomplete) {
			handleAvailable();
			LockSupport.park();
		}
		//one last time to handle any missed chunks:
		handleAvailable();
		setProcessingComplete(true);
		executor.shutdown();
		try {
			while (!executor.isTerminated())
				executor.awaitTermination(2000, TimeUnit.MILLISECONDS);
		} catch (InterruptedException e) {}
		if (log.isDebugEnabled())
			log.debug("calling unpark at time "+System.currentTimeMillis());
		LockSupport.unpark(getWaitingThread());
	}
	
	public void moreData() {
		LockSupport.unpark(getMyThread());
	}
	
	private String remain = null;
	private long submittedCount = 0;
	
	
	public void handleAvailable() {
		int length = 100000;
		Reader reader = null;
		try {
			reader = new InputStreamReader(in, "UTF-8");
		} catch (UnsupportedEncodingException e) {
			e.printStackTrace();
		}
		String nextNibble = slurp(reader, length);
		try {
			while (StringUtils.isNotEmpty(nextNibble)) {
				if (StringUtils.isNotBlank(remain))
					nextNibble = remain+nextNibble;
				String onlyCompleteLines = StringUtils.substringBeforeLast(nextNibble, "\n")+"\n";
				state.processFile(onlyCompleteLines);
				if (log.isDebugEnabled()) {
					long newSubmit = StringUtils.countMatches(onlyCompleteLines, "\n");
					submittedCount += newSubmit;
					log.debug("ChunkConsumingThread submitted "+newSubmit+" new items, for a total of "+submittedCount);
				}
				remain = StringUtils.substringAfterLast(nextNibble, "\n");
				nextNibble = slurp(reader, length);
			}
			if (isDataComplete()) {
				log.debug("async file processing complete");
				state.endOfData();
			}
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
	

	public boolean isDataComplete() {
		return datacomplete;
	}

	public void setDataComplete(boolean complete) {
		this.datacomplete = complete;
	}

	public Thread getMyThread() {
		return myThread;
	}

	public void setMyThread(Thread myThread) {
		this.myThread = myThread;
	}
	
	protected String slurp(final Reader in, final int bufferSize) {
		
		final char[] buffer = new char[bufferSize];
		final StringBuilder out = new StringBuilder();
		try {
			int rsz = in.read(buffer, 0, buffer.length);
			if (rsz < 0)
				return null;
			out.append(buffer, 0, rsz);
			
		} catch (IOException ex) {
			ex.printStackTrace();
		}
		if (out.length() == 0)
			return null;
		return out.toString();
	}

	public boolean isProcessingComplete() {
		return processingcomplete;
	}

	public void setProcessingComplete(boolean processingcomplete) {
		this.processingcomplete = processingcomplete;
	}

	public Thread getWaitingThread() {
		return waitingThread;
	}

	public void setWaitingThread(Thread waitingThread) {
		this.waitingThread = waitingThread;
	}
}



