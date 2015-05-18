/*
 *
 *  * Copyright 2015 Skymind,Inc.
 *  *
 *  *    Licensed under the Apache License, Version 2.0 (the "License");
 *  *    you may not use this file except in compliance with the License.
 *  *    You may obtain a copy of the License at
 *  *
 *  *        http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  *    Unless required by applicable law or agreed to in writing, software
 *  *    distributed under the License is distributed on an "AS IS" BASIS,
 *  *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  *    See the License for the specific language governing permissions and
 *  *    limitations under the License.
 *
 *
 */

package org.nd4j.linalg.jcublas.context;

import java.util.HashMap;
import java.util.Map;

import com.google.common.collect.*;

import jcuda.jcublas.JCublas2;
import jcuda.jcublas.cublasHandle;
import jcuda.CudaException;
import jcuda.driver.CUcontext;
import jcuda.driver.CUdevice;
import jcuda.driver.CUresult;
import jcuda.driver.CUstream;
import jcuda.driver.CUstream_flags;
import jcuda.driver.JCudaDriver;
import jcuda.runtime.JCuda;
import jcuda.runtime.cudaStream_t;

import org.nd4j.linalg.factory.Nd4j;

import static jcuda.driver.JCudaDriver.*;

/**
 * A multithreaded version derived
 * from the cuda launcher util
 * by the authors of jcuda.
 *
 * This class handles managing cuda contexts
 * across multiple devices and threads.
 *
 *
 * @author Adam Gibson
 */
public class ContextHolder {

    private Map<Integer,CUdevice> devices = new HashMap<>();
    private Map<Integer, CUcontext> deviceIDContexts = new HashMap<>();
    private Map<String,Integer> threadNameToDeviceNumber = new HashMap<>();
    private Table<CUcontext,String,CUstream> contextStreams = HashBasedTable.create();
    private Table<CUcontext,String,cudaStream_t> cudaStreams = HashBasedTable.create();
    private Map<String, cublasHandle> handleMap = new HashMap<>();
    private int numDevices = 0;
    private static ContextHolder INSTANCE;
    
    private ContextHolder(){
        getNumDevices();
    }

    /**
     * Singleton pattern
     * @return the instance for the context holder.
     */
    public static ContextHolder getInstance() {
        if(INSTANCE == null) {
            INSTANCE = new ContextHolder();
            Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
                @Override
                public void run() {
                    INSTANCE.destroy();
                }
            }));
        }
        return INSTANCE;
    }


    private void getNumDevices() {
        int count[] = new int[1];
        cuDeviceGetCount(count);
        numDevices = count[0];
        if(numDevices < 1)
           numDevices = 1;
    }


    /**
     * Synchronized the stream.
     * This should be run after
     * every operation.
     */
    public static void syncStream() {
        JCuda.cudaStreamSynchronize(getInstance().getCudaStream());
    }

    /**
     * Get the device number for a particular host thread
     * @return the device for the given host thread
     *
     */
    public int getDeviceForThread() {
        if(numDevices > 1) {
            Integer device =  threadNameToDeviceNumber.get(Thread.currentThread().getName());
            if(device == null) {
                device = Nd4j.getRandom().nextInt(numDevices);
                threadNameToDeviceNumber.put(Thread.currentThread().getName(),device);
                return device;
            }
        }

        return 0;
    }


    /**
     * Get the handle for the current thread
     * @return the handle for the current thread
     */
    public  cublasHandle getHandle() {
        cublasHandle handle =  handleMap.get(Thread.currentThread().getName());
        if(handle != null)
            return handle;
        handle = new cublasHandle();
        JCublas2.cublasCreate(handle);
        handleMap.put(Thread.currentThread().getName(),handle);
        return handle;
    }

    /**
     * Retrieve a context for use with the current thread
     * and the given device
     * @return the context for the given device and thread
     */
    public   CUcontext getContext() {
        return getContext(getDeviceForThread());
    }

    /**
     * Get the stream for the current thread
     * based on the device for the thread
     * @return the stream for the device and
     * thread
     */
    public synchronized cudaStream_t getCudaStream() {
        Thread currentThread = Thread.currentThread();
        CUcontext ctx = getContext(getDeviceForThread());
        cudaStream_t stream = cudaStreams.get(ctx, currentThread.getName());

        if(stream == null) {
            stream = new cudaStream_t();
            checkResult(JCudaDriver.cuCtxSetCurrent(ctx));
            JCuda.cudaStreamCreate(stream);
            checkResult(JCuda.cudaStreamCreate(stream));
            cudaStreams.put(ctx, currentThread.getName(), stream);
        }

        return stream;
    }


    /**
     * Get the stream for the current thread
     * based on the device for the thread
     * @return the stream for the device and
     * thread
     */
    public synchronized CUstream getStream() {
    	Thread currentThread = Thread.currentThread();
    	CUcontext ctx = getContext(getDeviceForThread());
    	CUstream stream = contextStreams.get(ctx, currentThread.getName());
    	
    	if(stream == null) {
    		stream = new CUstream();
    		checkResult(JCudaDriver.cuCtxSetCurrent(ctx));
    		checkResult(JCudaDriver.cuStreamCreate(stream, CUstream_flags.CU_STREAM_DEFAULT));
    		contextStreams.put(ctx, currentThread.getName(), stream);
    	}
    	
    	return stream;
    }

	private void checkResult(int result) {
		if (result != CUresult.CUDA_SUCCESS) {
		    throw new CudaException("Failed to create a stream: "+ CUresult.stringFor(result));
		}
	}

    /**
     * Retrieve a context for use with the current thread
     * and the given device
     * @param deviceToUse the device to use
     * @return the t
     */
    public  synchronized CUcontext getContext(int deviceToUse) {
        
        CUcontext ctx = deviceIDContexts.get(deviceToUse);
        if(ctx == null) {
            ctx = new CUcontext();
            for(int device = 0; device < numDevices; device++) {
                initialize(ctx,device);
                CUdevice currDevice = createDevice(ctx, device);
                devices.put(device,currDevice);
                deviceIDContexts.put(device,ctx);
            }

        }

        return ctx;
    }


    /**
     * Initializes this KernelLauncher. This method will try to
     * initialize the JCuda driver API. Then it will try to
     * attach to the current CUDA context. If no active CUDA
     * context exists, then it will try to create one, for
     * the device which is specified by the current
     * deviceNumber.
     *
     * @throws CudaException If it is neither possible to
     * attach to an existing context, nor to create a new
     * context.
     */
    private void initialize(CUcontext context,int deviceNumber) {
        int result = cuInit(deviceNumber);
        if (result != CUresult.CUDA_SUCCESS) {
            throw new CudaException(
                    "Failed to initialize the driver: "+
                            CUresult.stringFor(result));
        }

        // Try to obtain the current context
        result = cuCtxGetCurrent(context);
        if (result != CUresult.CUDA_SUCCESS)
        {
            throw new CudaException(
                    "Failed to obtain the current context: "+
                            CUresult.stringFor(result));
        }

        // If the context is 'null', then a new context
        // has to be created.
        CUcontext nullContext = new CUcontext();
        if (context.equals(nullContext))
            createContext(context,deviceNumber);

    }

    /**
     * Tries to create a context for device 'deviceNumber'.
     *
     * @throws CudaException If the device can not be
     * accessed or the context can not be created
     */
    private void createContext(CUcontext context,int deviceNumber) {
        CUdevice device = new CUdevice();
        int result = cuDeviceGet(device, deviceNumber);
        if (result != CUresult.CUDA_SUCCESS) {
            throw new CudaException(
                    "Failed to obtain a device: "+
                            CUresult.stringFor(result));
        }

        result = cuCtxCreate(context, 0, device);
        if (result != CUresult.CUDA_SUCCESS) {
            throw new CudaException(
                    "Failed to create a context: "+
                            CUresult.stringFor(result));
        }

    }

    /**
     * Create a context for the given device
     * @param context the context to create
     * @param deviceNumber the device number to create the context for
     * @return the created device
     */
    public static CUdevice createDevice(CUcontext context,int deviceNumber) {
        CUdevice device = new CUdevice();
        int result = cuDeviceGet(device, deviceNumber);
        if (result != CUresult.CUDA_SUCCESS) {
            throw new CudaException(
                    "Failed to obtain a device: "+
                            CUresult.stringFor(result));
        }

        result = cuCtxCreate(context, 0, device);
        if (result != CUresult.CUDA_SUCCESS) {
            throw new CudaException(
                    "Failed to create a context: "+
                            CUresult.stringFor(result));
        }

        return device;
    }

    /**
     * Returns the available devices
     * delimited by device,thread
     * @return the available devices
     */
    public Map<Integer, CUdevice> getDevices() {
        return devices;
    }

    /**
     * Returns the available contexts
     * based on device and thread name
     * @return the context
     */
    public Map<Integer, CUcontext> getDeviceIDContexts() {
        return deviceIDContexts;
    }

    public void destroy() {

        for(cudaStream_t stream : cudaStreams.values()) {
            JCuda.cudaStreamDestroy(stream);
        }
        for(CUstream stream : contextStreams.values()) {
            cuStreamDestroy(stream);
        }
        for(CUcontext ctx : deviceIDContexts.values()) {
            cuCtxDestroy(ctx);
        }



    }

}