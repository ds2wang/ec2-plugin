/*
 * The MIT License
 *
 * Copyright (c) 2004-, Kohsuke Kawaguchi, Sun Microsystems, Inc., and a number of other of contributors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package hudson.plugins.ec2;

import hudson.model.Descriptor;
import hudson.model.Descriptor.FormException;
import hudson.model.Executor;
import hudson.model.Hudson;
import hudson.model.Label;
import hudson.slaves.RetentionStrategy;
import hudson.util.StreamTaskListener;
import hudson.util.TimeUnit2;

import java.io.IOException;
import java.io.StringWriter;
import java.util.logging.Logger;

import javax.servlet.ServletException;

import jenkins.slaves.iterators.api.NodeIterator;

import org.kohsuke.stapler.DataBoundConstructor;

import antlr.ANTLRException;

import com.amazonaws.AmazonClientException;
import com.amazonaws.services.ec2.model.DescribeSpotInstanceRequestsRequest;
import com.amazonaws.services.ec2.model.Instance;
import com.amazonaws.services.ec2.model.SpotInstanceRequest;

/**
 * {@link RetentionStrategy} for EC2.
 *
 * @author Kohsuke Kawaguchi
 */
public class EC2RetentionStrategy extends RetentionStrategy<EC2Computer> {
    /** Number of minutes of idleness before an instance should be terminated.
	    A value of zero indicates that the instance should never be automatically terminated */
    public final int idleTerminationMinutes;


    @DataBoundConstructor
    public EC2RetentionStrategy(String idleTerminationMinutes) {
        if (idleTerminationMinutes == null || idleTerminationMinutes.trim() == "") {
            this.idleTerminationMinutes = 0;
        } else {
            int value = 30;
            try {
                value = Integer.parseInt(idleTerminationMinutes);
            } catch (NumberFormatException nfe) {
                LOGGER.info("Malformed default idleTermination value: " + idleTerminationMinutes); 
            }

            this.idleTerminationMinutes = value;
        }
    }

    @Override
    public synchronized long check(EC2Computer c) {
    	LOGGER.severe("begin check: " );
    	
        /* If we've been told never to terminate, then we're done. */
        if  (idleTerminationMinutes == 0)
        	return 1;
        String labelstr = c.getNode().getLabelString();
        int numIdleSlaves = countIdleSlaves(labelstr, c) ;
        try {
			Label l= Label.parseExpression(labelstr);
		} catch (ANTLRException e1) {
			// TODO Auto-generated catch block
			e1.printStackTrace();
		}

        LOGGER.severe("Idle slaves: " + numIdleSlaves);
        if (c.isIdle() && c.isOnline() && !disabled) {
            // TODO: really think about the right strategy here
        	
            final long idleMilliseconds = System.currentTimeMillis() - c.getIdleStartMilliseconds();
            if (idleMilliseconds > TimeUnit2.MINUTES.toMillis(idleTerminationMinutes)) {
            	
                SlaveTemplate t = null;
                
                for (SlaveTemplate temp:c.getCloud().getTemplates()){
                	if (temp.getLabelString().equals(labelstr)){
                		t=temp;
                	}
                }
               // LOGGER.info("PIWINDOW: "+ t.getPIWindow().get(0).getStartTime());
                int numPrimedInstances = t.getNumPrimedInstances();
            	if(numIdleSlaves > numPrimedInstances){ //determine if ok to terminate
                    LOGGER.info("Idle timeout: "+c.getName());
                    c.getNode().idleTimeout();
            	}else{
            		for(int i=0; i < numPrimedInstances - numIdleSlaves; i++){
	            		//EC2AbstractSlave newSlave = c.getCloud().doProvision(t);
	            		//newSlave.toComputer().connect(false);
            		}
            	}
            }
        }
        return 1;
    }

    
    public int countIdleSlaves(String labelstr, EC2Computer c) {
    	int numIdleSlaves = 0;
    	for(EC2OndemandSlave n : NodeIterator.nodes(EC2OndemandSlave.class)){
    		try{
    			if(n.getLabelString().equals(labelstr)&&n.getComputer().isOnline()){
		    		for (Executor ex:n.getComputer().getExecutors()){
		    			if(ex.isIdle()){
		    				numIdleSlaves++;
		    				break;
		    			}
		    				
		    		}
    			}
    		}catch( Exception e){
    			LOGGER.info("some exception :"+e.getMessage());
    		}
    		LOGGER.info("ondemandslave ++ ");
			
		}

        return numIdleSlaves;
        
    }

    /**
     * Try to connect to it ASAP.
     */
    @Override
    public void start(EC2Computer c) {
        c.connect(false);
    }

    // no registration since this retention strategy is used only for EC2 nodes that we provision automatically.
    // @Extension
    public static class DescriptorImpl extends Descriptor<RetentionStrategy<?>> {
        @Override
		public String getDisplayName() {
            return "EC2";
        }
    }

    private static final Logger LOGGER = Logger.getLogger(EC2RetentionStrategy.class.getName());

    public static boolean disabled = Boolean.getBoolean(EC2RetentionStrategy.class.getName()+".disabled");
}
