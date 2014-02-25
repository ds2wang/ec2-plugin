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

import hudson.Extension;
import hudson.model.PeriodicWork;
import hudson.model.Computer;
import hudson.model.Descriptor;
import hudson.model.Executor;
import hudson.model.Hudson;
import hudson.model.Hudson.CloudList;
import hudson.model.Label;
import hudson.model.LoadStatistics;
import hudson.model.Node;
import hudson.slaves.Cloud;
import hudson.slaves.NodeProvisioner;
import hudson.slaves.NodeProvisioner.PlannedNode;
import hudson.util.FormValidation;
import hudson.util.Secret;
import hudson.util.StreamTaskListener;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.net.MalformedURLException;
import java.net.URL;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.HashMap;
import java.util.concurrent.Callable;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.servlet.ServletException;

import jenkins.model.Jenkins;
import jenkins.slaves.iterators.api.NodeIterator;

import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.StaplerResponse;

import com.amazonaws.AmazonClientException;
import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.services.ec2.AmazonEC2;
import com.amazonaws.services.ec2.AmazonEC2Client;
import com.amazonaws.services.ec2.model.CreateKeyPairRequest;
import com.amazonaws.services.ec2.model.DescribeSpotInstanceRequestsRequest;
import com.amazonaws.services.ec2.model.Instance;
import com.amazonaws.services.ec2.model.InstanceStateName;
import com.amazonaws.services.ec2.model.InstanceType;
import com.amazonaws.services.ec2.model.KeyPair;
import com.amazonaws.services.ec2.model.KeyPairInfo;
import com.amazonaws.services.ec2.model.Reservation;
import com.amazonaws.services.ec2.model.SpotInstanceRequest;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3Client;
import com.amazonaws.services.s3.model.GeneratePresignedUrlRequest;


/**
 * Hudson's view of EC2.
 *
 * @author Kohsuke Kawaguchi
 */
public abstract class EC2Cloud extends Cloud {

	public static final String DEFAULT_EC2_HOST = "us-east-1";
	public static final String EC2_URL_HOST = "ec2.amazonaws.com";

    private final String accessId;
    private final Secret secretKey;
    protected final EC2PrivateKey privateKey;

    /**
     * Upper bound on how many instances we may provision.
     */
    public final int instanceCap;
    private final List<? extends SlaveTemplate> templates;
    private transient KeyPair usableKeyPair;

    protected transient AmazonEC2 connection;

	private static AWSCredentials awsCredentials;

    /* Track the count per-AMI identifiers for AMIs currently being
     * provisioned, but not necessarily reported yet by Amazon.
     */
    private static HashMap<String, Integer> provisioningAmis = new HashMap<String, Integer>();

    protected EC2Cloud(String id, String accessId, String secretKey, String privateKey, String instanceCapStr, List<? extends SlaveTemplate> templates) {
        super(id);
        this.accessId = accessId.trim();
        this.secretKey = Secret.fromString(secretKey.trim());
        this.privateKey = new EC2PrivateKey(privateKey);

        if(templates==null) {
            this.templates=Collections.emptyList();
        } else {
            this.templates=templates;
        }

        if(instanceCapStr.equals("")) {
            this.instanceCap = Integer.MAX_VALUE;
        } else {
            this.instanceCap = Integer.parseInt(instanceCapStr);
        }

        readResolve(); // set parents
    }

    public abstract URL getEc2EndpointUrl() throws IOException;
    public abstract URL getS3EndpointUrl() throws IOException;

    protected Object readResolve() {
        for (SlaveTemplate t : templates)
            t.parent = this;
        return this;
    }

    public String getAccessId() {
        return accessId;
    }

    public String getSecretKey() {
        return secretKey.getEncryptedValue();
    }

    public EC2PrivateKey getPrivateKey() {
        return privateKey;
    }

    public String getInstanceCapStr() {
        if(instanceCap==Integer.MAX_VALUE)
            return "";
        else
            return String.valueOf(instanceCap);
    }

    public List<SlaveTemplate> getTemplates() {
        return Collections.unmodifiableList(templates);
    }

    public SlaveTemplate getTemplate(String template) {
        for (SlaveTemplate t : templates) {
            if(t.description.equals(template)) {
                return t;
            }
        }
        return null;
    }

    /**
     * Gets {@link SlaveTemplate} that has the matching {@link Label}.
     */
    public SlaveTemplate getTemplate(Label label) {
        for (SlaveTemplate t : templates) {
        	if(label == null || label.matches(t.getLabelSet())) {
                return t;
        	}
        }
        return null;
    }

    /**
     * Gets the {@link KeyPairInfo} used for the launch.
     */
    public synchronized KeyPair getKeyPair() throws AmazonClientException, IOException {
        if(usableKeyPair==null)
            usableKeyPair = privateKey.find(connect());
        return usableKeyPair;
    }

    /**
     * Counts the number of instances in EC2 currently running that are using the specifed image.
     *
     * @param ami If AMI is left null, then all instances are counted.
     * <p>
     * This includes those instances that may be started outside Hudson.
     */
    public int countCurrentEC2Slaves(String ami) throws AmazonClientException {
        int n=0;
        for (Reservation r : connect().describeInstances().getReservations()) {
            for (Instance i : r.getInstances()) {
                if (ami == null || ami.equals(i.getImageId())) {
                    InstanceStateName stateName = InstanceStateName.fromValue(i.getState().getName());
                    if (stateName == InstanceStateName.Pending || stateName == InstanceStateName.Running)
                        n++;
                }
            }
        }
        return n;
    }

    /**
     * Debug command to attach to a running instance.
     */
    public void doAttach(StaplerRequest req, StaplerResponse rsp, @QueryParameter String id) throws ServletException, IOException, AmazonClientException {
        checkPermission(PROVISION);
        SlaveTemplate t = getTemplates().get(0);

        StringWriter sw = new StringWriter();
        StreamTaskListener listener = new StreamTaskListener(sw);
        EC2AbstractSlave node = t.attach(id,listener);
        Hudson.getInstance().addNode(node);

        rsp.sendRedirect2(req.getContextPath()+"/computer/"+node.getNodeName());
    }
    public EC2AbstractSlave doProvision( SlaveTemplate t) {
        checkPermission(PROVISION);
        if(t==null) {
            return null;
        }

        LOGGER.severe("attempt create slave from retention!");
        StringWriter sw = new StringWriter();
        
        try {
        	StreamTaskListener listener = new StreamTaskListener(sw);
        	LOGGER.severe("attempt provision");
            EC2AbstractSlave node = t.provision(listener);
            LOGGER.severe("finally done");
            //Jenkins.getInstance().addNode(node);
            Hudson.getInstance().addNode(node);
            LOGGER.severe("finally done2");
            return node;
        } catch (IOException e) {
        	LOGGER.severe("Mess up at addnode");
        	return null;
        } catch (AmazonClientException e) {
        	LOGGER.severe("some Amazon error");
        	return null;
        }
    }
    public void doProvision(StaplerRequest req, StaplerResponse rsp, @QueryParameter String template) throws ServletException, IOException {
        checkPermission(PROVISION);
        if(template==null) {
            sendError("The 'template' query parameter is missing",req,rsp);
            return;
        }
        SlaveTemplate t = getTemplate(template);
        if(t==null) {
            sendError("No such template: "+template,req,rsp);
            return;
        }

        StringWriter sw = new StringWriter();
        StreamTaskListener listener = new StreamTaskListener(sw);
        try {
            EC2AbstractSlave node = t.provision(listener);
            Hudson.getInstance().addNode(node);

            rsp.sendRedirect2(req.getContextPath()+"/computer/"+node.getNodeName());
        } catch (AmazonClientException e) {
            req.setAttribute("exception", e);
            sendError(e.getMessage(),req,rsp);
        }
    }


    /**
     * Check for the count of EC2 slaves and determine if a new slave can be added.
     * Takes into account both what Amazon reports as well as an internal count
     * of slaves currently being "provisioned".
     */
    private boolean addProvisionedSlave(String ami, int amiCap) throws AmazonClientException {
        int estimatedTotalSlaves = countCurrentEC2Slaves(null);
        int estimatedAmiSlaves = countCurrentEC2Slaves(ami);

        synchronized (provisioningAmis) {
            int currentProvisioning;

            for (int amiCount : provisioningAmis.values()) {
                estimatedTotalSlaves += amiCount;
            }
            try {
                currentProvisioning = provisioningAmis.get(ami);
            }
            catch (NullPointerException npe) {
                currentProvisioning = 0;
            }

            estimatedAmiSlaves += currentProvisioning;

            if(estimatedTotalSlaves >= instanceCap) {
                LOGGER.log(Level.INFO, "Total instance cap of " + instanceCap +
                                    " reached, not provisioning.");
                return false;      // maxed out
            }

            if (estimatedAmiSlaves >= amiCap) {
                LOGGER.log(Level.INFO, "AMI Instance cap of " + amiCap +
                                    " reached for ami " + ami +
                                    ", not provisioning.");
                return false;      // maxed out
            }

            LOGGER.log(Level.INFO,
                            "Provisioning for AMI " + ami + "; " +
                            "Estimated number of total slaves: "
                            + String.valueOf(estimatedTotalSlaves) + "; " +
                            "Estimated number of slaves for ami "
                            + ami + ": "
                            + String.valueOf(estimatedAmiSlaves)
                    );

            provisioningAmis.put(ami, currentProvisioning + 1);
            return true;
        }
    }

    /**
     * Decrease the count of slaves being "provisioned".
     */
    private void decrementAmiSlaveProvision(String ami) {
        synchronized (provisioningAmis) {
            int currentProvisioning;
            try {
                currentProvisioning = provisioningAmis.get(ami);
            } catch(NullPointerException npe) {
                return;
            }
            provisioningAmis.put(ami, Math.max(currentProvisioning - 1, 0));
        }
    }
    public int countIdleSlaves(String labelstr) {
    	int numIdleSlaves = 0;
    	for(EC2OndemandSlave n : NodeIterator.nodes(EC2OndemandSlave.class)){
    		try{
    			if(n.getLabelString().equals(labelstr)){
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
    @Override
	public Collection<PlannedNode> provision(Label label, int excessWorkload) {
    	int excessWorkloadStart = excessWorkload;
    	int slavesUsed = 0;
    	LOGGER.log(Level.INFO, "Excess workload start: " + excessWorkload);
        try {
            // Count number of pending executors from spot requests
			for(EC2SpotSlave n : NodeIterator.nodes(EC2SpotSlave.class)){
				// If the slave is online then it is already counted by Jenkins
				// We only want to count potential additional Spot instance slaves
				if (n.getComputer().isOffline()){
					DescribeSpotInstanceRequestsRequest dsir =
							new DescribeSpotInstanceRequestsRequest().withSpotInstanceRequestIds(n.getSpotInstanceRequestId());

					for(SpotInstanceRequest sir : connect().describeSpotInstanceRequests(dsir).getSpotInstanceRequests()) {
						// Count Spot requests that are open and still have a chance to be active
						// A request can be active and not yet registered as a slave. We check above
						// to ensure only unregistered slaves get counted
						if(sir.getState().equals("open") || sir.getState().equals("active")){
							slavesUsed++;
							excessWorkload -= n.getNumExecutors();
						}
					}
				}
			}
			int numIdleSlaves = countIdleSlaves(label.getName()) - slavesUsed;
			
			LOGGER.log(Level.INFO, "Excess workload after pending Spot instances: " + excessWorkload);

            List<PlannedNode> r = new ArrayList<PlannedNode>();

            final SlaveTemplate t = getTemplate(label);
            int primedInstances = t.getNumPrimedInstances();
            int primedInstancesNeeded = primedInstances - numIdleSlaves;
            LOGGER.log(Level.INFO, "Primed instances needed: " + primedInstancesNeeded +" = primedInstances: "+primedInstances+ " - numIdleSlaves: "+numIdleSlaves);
            LOGGER.log(Level.INFO, "labelName: "+label.getName());
            //if(excessWorkloadStart == excessWorkload)
            Date date = new Date();   // given date
            Calendar calendar = GregorianCalendar.getInstance(); // creates a new calendar instance
            calendar.setTime(date);   // assigns calendar to given date 
            DateFormat dateFormat = new SimpleDateFormat("HH:mm");
            int hour= calendar.get(Calendar.HOUR_OF_DAY); // gets hour in 24h format
            int minutes = calendar.get(Calendar.MINUTE);        // gets hour in 12h format
            LOGGER.log(Level.INFO, "checking time period: "+dateFormat.format(calendar.getTime()));
            if(isInPIWindow(t, hour, minutes)){
            	excessWorkload += primedInstancesNeeded * t.getNumExecutors();
            	LOGGER.log(Level.INFO, "in time period");
            }else{
            	LOGGER.log(Level.INFO, "not in time period");
            }
            int amiCap = t.getInstanceCap();

            while (excessWorkload>0) {

                if (!addProvisionedSlave(t.ami, amiCap)) {
                    break;
                }

                r.add(new PlannedNode(t.getDisplayName(),
                        Computer.threadPoolForRemoting.submit(new Callable<Node>() {
                            public Node call() throws Exception {
                                // TODO: record the output somewhere
                                try {
                                    EC2AbstractSlave s = t.provision(new StreamTaskListener(System.out));
                                    Hudson.getInstance().addNode(s);
                                    LOGGER.log(Level.INFO, "Added node");
                                    // EC2 instances may have a long init script. If we declare
                                    // the provisioning complete by returning without the connect
                                    // operation, NodeProvisioner may decide that it still wants
                                    // one more instance, because it sees that (1) all the slaves
                                    // are offline (because it's still being launched) and
                                    // (2) there's no capacity provisioned yet.
                                    //
                                    // deferring the completion of provisioning until the launch
                                    // goes successful prevents this problem.
                                    s.toComputer().connect(false).get();
                                    return s;
                                }
                                finally {
                                    decrementAmiSlaveProvision(t.ami);
                                }
                            }
                        })
                        ,t.getNumExecutors()));

                excessWorkload -= t.getNumExecutors();

            }
            return r;
        } catch (AmazonClientException e) {
            LOGGER.log(Level.WARNING,"Failed to count the # of live instances on EC2",e);
            return Collections.emptyList();
        }
    }

    @Override
	public boolean canProvision(Label label) {
        return getTemplate(label)!=null;
    }
    public static List<EC2Cloud> toEC2Cloud(CloudList clist){
    	List<EC2Cloud> list=new ArrayList<EC2Cloud>();
    	for(Cloud c:clist)
    		list.add((EC2Cloud)c);
    	return list;
    }
    /**
     * Connects to EC2 and returns {@link AmazonEC2}, which can then be used to communicate with EC2.
     */
    public synchronized AmazonEC2 connect() throws AmazonClientException {
        try {
            if (connection == null) {
                connection = connect(accessId, secretKey, getEc2EndpointUrl());
            }
            return connection;
        } catch (IOException e) {
            throw new AmazonClientException("Failed to retrieve the endpoint",e);
        }
    }

    /***
     * Connect to an EC2 instance.
     * @return {@link AmazonEC2} client
     */
    public static AmazonEC2 connect(String accessId, String secretKey, URL endpoint) {
        return connect(accessId, Secret.fromString(secretKey), endpoint);
    }

    /***
     * Connect to an EC2 instance.
     * @return {@link AmazonEC2} client
     */
    public synchronized static AmazonEC2 connect(String accessId, Secret secretKey, URL endpoint) {
    	awsCredentials = new BasicAWSCredentials(accessId, Secret.toString(secretKey));
        AmazonEC2 client = new AmazonEC2Client(awsCredentials);
        client.setEndpoint(endpoint.toString());
        return client;
    }

    
    public static boolean isInPIWindow(SlaveTemplate t, int hour, int minute){
    	EC2PIWindow window1 = t.getPIWindow().get(0);
    	int curTime = hour*60+minute;
    	int start, end;
        if ((window1.getStartTime() == null || window1.getStartTime().trim() == "")
        		&& (window1.getEndTime() == null || window1.getEndTime().trim() == "")) return true;
        try {
        	String [] startTimeStr =  window1.getStartTime().trim().split(":");
        	String [] endTimeStr =  window1.getEndTime().trim().split(":");
        	LOGGER.log(Level.INFO, "startTime:" + window1.getStartTime().trim());
        	LOGGER.log(Level.INFO, "endTime:" + window1.getEndTime().trim());
            int startHour = Integer.parseInt(startTimeStr[0]);
            int startMin = Integer.parseInt(startTimeStr[1]);
            int endHour = Integer.parseInt(endTimeStr[0]);
            int endMin = Integer.parseInt(endTimeStr[1]);
            if(endHour*60 + endMin < startHour*60 + startMin){
                if(curTime<startMin + startHour*60){
                      start = startHour*60 + startMin-1440;
                      end = endHour*60 + endMin;
                }else{
                      start = startHour*60 + startMin;
                      end = endHour*60 + endMin + 1440;
                }

         }else{
                start = startHour*60 + startMin;
                end = endHour*60 + endMin;
         }
         if(curTime >= start && curTime < end)
                return true;
        } catch ( NumberFormatException nfe ) {
        	
        } catch (Exception e){
        	
        }
    	return false;
    }
    /***
     * Convert a configured hostname like 'us-east-1' to a FQDN or ip address
     */
    public static String convertHostName(String ec2HostName) {
        if (ec2HostName == null || ec2HostName.length()==0)
            ec2HostName = DEFAULT_EC2_HOST;
        if (!ec2HostName.contains("."))
            ec2HostName = ec2HostName + "." + EC2_URL_HOST;
        return ec2HostName;
    }

    /***
     * Convert a user entered string into a port number
     * "" -> -1 to indicate default based on SSL setting
     */
    public static Integer convertPort(String ec2Port) {
        if (ec2Port == null || ec2Port.length() == 0)
            return -1;
        return Integer.parseInt(ec2Port);
    }

    /**
     * Computes the presigned URL for the given S3 resource.
     *
     * @param path
     *      String like "/bucketName/folder/folder/abc.txt" that represents the resource to request.
     */
    public URL buildPresignedURL(String path) throws IOException, AmazonClientException {
        long expires = System.currentTimeMillis()+60*60*1000;
        GeneratePresignedUrlRequest request = new GeneratePresignedUrlRequest(path, Secret.toString(secretKey));
        request.setExpiration(new Date(expires));
        AmazonS3 s3 = new AmazonS3Client(awsCredentials);
        return s3.generatePresignedUrl(request);
    }

    /* Parse a url or return a sensible error */
    public static URL checkEndPoint(String url) throws FormValidation {
        try {
            return new URL(url);
        } catch (MalformedURLException ex) {
            throw FormValidation.error("Endpoint URL is not a valid URL");
        }
    }


    public static abstract class DescriptorImpl extends Descriptor<Cloud> {
        public InstanceType[] getInstanceTypes() {
            return InstanceType.values();
        }

        public FormValidation doCheckAccessId(@QueryParameter String value) throws IOException, ServletException {
            return FormValidation.validateBase64(value,false,false,Messages.EC2Cloud_InvalidAccessId());
        }

        public FormValidation doCheckSecretKey(@QueryParameter String value) throws IOException, ServletException {
            return FormValidation.validateBase64(value,false,false,Messages.EC2Cloud_InvalidSecretKey());
        }

        public FormValidation doCheckPrivateKey(@QueryParameter String value) throws IOException, ServletException {
            boolean hasStart=false,hasEnd=false;
            BufferedReader br = new BufferedReader(new StringReader(value));
            String line;
            while ((line = br.readLine()) != null) {
                if (line.equals("-----BEGIN RSA PRIVATE KEY-----"))
                    hasStart=true;
                if (line.equals("-----END RSA PRIVATE KEY-----"))
                    hasEnd=true;
            }
            if(!hasStart)
                return FormValidation.error("This doesn't look like a private key at all");
            if(!hasEnd)
                return FormValidation.error("The private key is missing the trailing 'END RSA PRIVATE KEY' marker. Copy&paste error?");
            return FormValidation.ok();
        }

        protected FormValidation doTestConnection( URL ec2endpoint,
                                     String accessId, String secretKey, String privateKey) throws IOException, ServletException {
            try {
                AmazonEC2 ec2 = connect(accessId, secretKey, ec2endpoint);
                ec2.describeInstances();

                if(accessId==null)
                    return FormValidation.error("Access ID is not specified");
                if(secretKey==null)
                    return FormValidation.error("Secret key is not specified");
                if(privateKey==null)
                    return FormValidation.error("Private key is not specified. Click 'Generate Key' to generate one.");

                if(privateKey.trim().length()>0) {
                    // check if this key exists
                    EC2PrivateKey pk = new EC2PrivateKey(privateKey);
                    if(pk.find(ec2)==null)
                        return FormValidation.error("The EC2 key pair private key isn't registered to this EC2 region (fingerprint is "+pk.getFingerprint()+")");
                }

                return FormValidation.ok(Messages.EC2Cloud_Success());
            } catch (AmazonClientException e) {
                LOGGER.log(Level.WARNING, "Failed to check EC2 credential",e);
                return FormValidation.error(e.getMessage());
            }
        }

        public FormValidation doGenerateKey(StaplerResponse rsp, URL ec2EndpointUrl, String accessId, String secretKey)
        		throws IOException, ServletException {
            try {
                AmazonEC2 ec2 = connect(accessId, secretKey, ec2EndpointUrl);
                List<KeyPairInfo> existingKeys = ec2.describeKeyPairs().getKeyPairs();

                int n = 0;
                while(true) {
                    boolean found = false;
                    for (KeyPairInfo k : existingKeys) {
                        if(k.getKeyName().equals("hudson-"+n))
                            found=true;
                    }
                    if(!found)
                        break;
                    n++;
                }

                CreateKeyPairRequest request = new CreateKeyPairRequest("hudson-" + n);
                KeyPair key = ec2.createKeyPair(request).getKeyPair();


                rsp.addHeader("script","findPreviousFormItem(button,'privateKey').value='"+key.getKeyMaterial().replace("\n","\\n")+"'");

                return FormValidation.ok(Messages.EC2Cloud_Success());
            } catch (AmazonClientException e) {
                LOGGER.log(Level.WARNING, "Failed to check EC2 credential",e);
                return FormValidation.error(e.getMessage());
            }
        }
    }
    @Extension
    public static class NodeProvisionerInvoker extends PeriodicWork {
        /**
         * Give some initial warm up time so that statically connected slaves
         * can be brought online before we start allocating more.
         */
    	 public static int INITIALDELAY = Integer.getInteger(NodeProvisioner.class.getName()+".initialDelay",LoadStatistics.CLOCK*10);
    	 public static int RECURRENCEPERIOD = Integer.getInteger(NodeProvisioner.class.getName()+".recurrencePeriod",LoadStatistics.CLOCK);
    	 
        @Override
        public long getInitialDelay() {
            return INITIALDELAY;
        }

        public long getRecurrencePeriod() {
            return RECURRENCEPERIOD;
        }

        @Override
        protected void doRun() {
            Jenkins h = Jenkins.getInstance();
            LOGGER.log(Level.INFO,"num lables :"+h.getLabels().size());
            int i=0;
            for( Label l : h.getLabels() ){
            	i++;
            	LOGGER.log(Level.INFO,"Label "+i);
            	LOGGER.log(Level.INFO,"Label "+i+"LabelName: "+l.getName());
            	for(EC2Cloud c:toEC2Cloud(h.clouds)){
            		if(c.canProvision(l))
            			c.provision(l, 0);
            	}
            }
        }
    }
    private static final Logger LOGGER = Logger.getLogger(EC2Cloud.class.getName());
}
