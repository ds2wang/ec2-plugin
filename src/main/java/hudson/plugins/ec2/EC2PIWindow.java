package hudson.plugins.ec2;

import hudson.Extension;
import hudson.model.AbstractDescribableImpl;
import hudson.model.Descriptor;
import org.kohsuke.stapler.DataBoundConstructor;

/**
 * @author David Wang
 */

public class EC2PIWindow extends AbstractDescribableImpl<EC2PIWindow> {
    private String startTime;
    private String endTime;

    public String getStartTime() {
        return startTime;
    }
    
    public String getEndTime() {
        return endTime;
    }
    
    @DataBoundConstructor
    public EC2PIWindow(String startTime, String endTime)  {
    	this.startTime = startTime.trim();
        this.endTime = endTime.trim();
    }
    
    @Extension
    public static class DescriptorImpl extends Descriptor<EC2PIWindow> {
        @Override
        public String getDisplayName() {
            return "Primed Instance Window";
        }
    }
}