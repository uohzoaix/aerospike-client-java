/*
 * Aerospike Client - Java Library
 *
 * Copyright 2014 by Aerospike, Inc. All rights reserved.
 *
 * Availability of this source code to partners and customers includes
 * redistribution rights covered by individual contract. Please check your
 * contract for exact rights and responsibilities.
 */
package com.aerospike.client.command;

import java.util.concurrent.ExecutorService;

import com.aerospike.client.AerospikeException;
import com.aerospike.client.ScanCallback;
import com.aerospike.client.cluster.Cluster;
import com.aerospike.client.cluster.Node;
import com.aerospike.client.policy.ScanPolicy;

public final class ScanExecutor {
	
	private final ExecutorService threadPool;
	private final ScanThread[] threads;
	private volatile Exception exception;
	private int nextThread;
	private boolean completed;
	
	public ScanExecutor(Cluster cluster, Node[] nodes, ScanPolicy policy, String namespace, String setName, ScanCallback callback, String[] binNames) {
		this.threadPool = cluster.getThreadPool();
		
		// Initialize threads.		
		threads = new ScanThread[nodes.length];
		
		for (int i = 0; i < nodes.length; i++) {
			ScanCommand command = new ScanCommand(nodes[i], policy, namespace, setName, callback, binNames);
			threads[i] = new ScanThread(command);
		}
		
		// Initialize maximum number of nodes to query in parallel.
		nextThread = (policy.maxConcurrentNodes == 0 || policy.maxConcurrentNodes >= threads.length)? threads.length : policy.maxConcurrentNodes;
	}
	
	public void scanParallel() throws AerospikeException {
		// Start threads. Use separate max because threadCompleted() may modify nextThread in parallel.
		int max = nextThread;

		for (int i = 0; i < max; i++) {
			threadPool.execute(threads[i]);
		}
		waitTillComplete();
		
		// Throw an exception if an error occurred.
		if (exception != null) {
			if (exception instanceof AerospikeException) {
				throw (AerospikeException)exception;		
			}
			else {
				throw new AerospikeException(exception);
			}		
		}
	}
	
	private void threadCompleted() {
	   	int index = -1;
		
		// Determine if a new thread needs to be started.
		synchronized (threads) {
			if (nextThread < threads.length) {
				index = nextThread++;
			}
		}
		
		if (index >= 0) {
			// Start new thread.
			threadPool.execute(threads[index]);
		}
		else {
			// All threads have been started. Check status.
			for (ScanThread thread : threads) {
				if (! thread.complete) {
					// Some threads have not finished. Do nothing.
					return;
				}
			}
			// All threads complete.
			notifyCompleted();
		}
	}

	private void stopThreads(Exception cause) {
    	synchronized (threads) {
    	   	if (exception != null) {
    	   		return;
    	   	}
	    	exception = cause;  		
    	}
    	
		for (ScanThread thread : threads) {
			try {
				thread.stop();
			}
			catch (Exception e) {
			}
		}
		notifyCompleted();
    }

	private synchronized void waitTillComplete() {
		while (! completed) {
			try {
				super.wait();
			}
			catch (InterruptedException ie) {
			}
		}
	}
	
	private synchronized void notifyCompleted() {
		completed = true;
		super.notify();
	}

    private final class ScanThread implements Runnable {
		private final ScanCommand command;
		private Thread thread;
		private boolean complete;

		public ScanThread(ScanCommand command) {
			this.command = command;
		}
		
		public void run() {
			thread = Thread.currentThread();
			
			try {
				if (command.isValid()) {
					command.execute();
				}
			}
			catch (Exception e) {
				// Terminate other scan threads.
				stopThreads(e);
			}
			complete = true;
			
		   	if (exception == null) {
				threadCompleted();
		   	}
		}
		
		public void stop() {
			command.stop();
			
			if (thread != null) {
				thread.interrupt();
			}
		}
	}
}
