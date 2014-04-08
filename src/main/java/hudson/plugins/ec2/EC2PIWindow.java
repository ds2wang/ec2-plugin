package hudson.plugins.ec2;

import hudson.Extension;
import hudson.model.AbstractDescribableImpl;
import hudson.model.Descriptor;
import hudson.util.FormValidation;

import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;

/**
 * @author David Wang
 */

public class EC2PIWindow extends AbstractDescribableImpl<EC2PIWindow> {
    private String startTime;
    private String endTime;
    private boolean [] days = new boolean [7];
    public String getStartTime() {
        return startTime;
    }
    
    public boolean inDays( int day){
    	return days[day];
    }
    
    public String getEndTime() {
        return endTime;
    }
    public boolean getMon(){
    	return days[0];
    }
    public boolean getTues(){
    	return days[1];
    }
    public boolean getWed(){
    	return days[2];
    }
    public boolean getThurs(){
    	return days[3];
    }
    public boolean getFri(){
    	return days[4];
    }
    public boolean getSat(){
    	return days[5];
    }
    public boolean getSun(){
    	return days[6];
    }
    @DataBoundConstructor
    /*
    public EC2PIWindow(String startTime, String endTime)  {
    	this.startTime = startTime.trim();
    	this.endTime = endTime.trim();
    }*/
    public EC2PIWindow(String startTime, String endTime, boolean mon, boolean tues, boolean wed, boolean thurs, boolean fri, boolean sat, boolean sun)  {
    	this.startTime = startTime.trim();
    	this.endTime = endTime.trim();
    	this.days[0]=mon; 
    	this.days[1]=tues;
    	this.days[2]=wed;
    	this.days[3]=thurs;
    	this.days[4]=fri;
    	this.days[5]=sat;
    	this.days[6]=sun;
    }


    
    @Extension
    public static class DescriptorImpl extends Descriptor<EC2PIWindow> {
        @Override
        public String getDisplayName() {
            return "Primed Instance Window";
        }
        @SuppressWarnings({ "static-method", "unused" }) 
        public FormValidation doCheckStartTime( @QueryParameter final String value) { 
			if (value == null || value.trim().equals("")) 
				return FormValidation.ok();
			try {
				String [] startTimeStr = value.trim().split(":");
				if(startTimeStr[1].length()!=2 || startTimeStr[0].length()>2)
					return FormValidation.error("Start Time must in the format hh:mm");
			    int startHour = Integer.parseInt(startTimeStr[0]);
			    int startMin = Integer.parseInt(startTimeStr[1]);
			    if(startHour >= 0 && startHour < 24 && startMin >= 0 && startMin < 60 )
			    	 return FormValidation.ok();
			} catch ( NumberFormatException nfe ) {
				
			} catch (Exception e){
				
			}
			return FormValidation.error("Start Time must in the format hh:mm");

        } 
        @SuppressWarnings({ "static-method", "unused" }) public 
        FormValidation doCheckEndTime( @QueryParameter final String value) { 
		    if (value == null || value.trim().equals("")) 
		    	return FormValidation.ok();
		    try {
		    	String [] endTimeStr =  value.trim().split(":");
		    	if(endTimeStr[1].length()!=2 || endTimeStr[0].length()>2)
		    		return FormValidation.error("End Time must in the format hh:mm");
		        int endHour = Integer.parseInt(endTimeStr[0]);
		        int endMin = Integer.parseInt(endTimeStr[1]);
		        if(endHour < 24 && endMin >= 0 && endMin < 60)
		        	 return FormValidation.ok();
		    } catch ( NumberFormatException nfe ) {
		    	
		    } catch (Exception e){
		    	
		    }
		    return FormValidation.error("End Time must in the format hh:mm");
        } 
    }
    
}