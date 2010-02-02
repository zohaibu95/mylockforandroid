package i4nc4mp.myLock;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.os.Bundle;
import android.os.Handler;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings.SettingNotFoundException;
import android.util.Log;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

//starting point is the alarmalert prompt.
//this window itself dismisses keyguard.

//we are going to call it up when the screen goes OFF
//then attempt to mediate which keys will cause a result at that point

//android color = #ffa4c639
//just thought you should know that


//lockdown and regular lockscreen are closely related
//during lockdown, we want to ensure to bring ourselves back if activity goes to background
//otherwise, we want to FINISH when we go to background in any fashion

//the focus change method handles this fine. droid-fu lets you distinguish if it's you or other apps taking focus away




//LIFE CYCLE
//Mediator waits for screen off. If flag ShouldLock was received from last exiting lockscreen (or True by first start)---
// ----------Flag PendingLock true & trigger 4 sec wait
//If user had forced sleep, causing immediate guard, then wakes before 4 sec, mediator fires a Dismiss activity
//If it was a timeout sleep, timer is aborted when user aborts by waking screen within the 5 sec
//Else, this activity successfully starts, so we send a start intent back to mediator to tell it to flag PendingLock back false
//This way mediator knows we got started

//If mediator gets a screen on and still has PendingLock, it would know we were just starting at on
//and can respond by doing the dismiss activity or sending a finish intent to us - this bug case seems to be avoided by the 4 sec delay

//our own lifecycle is to detect focus changes and user key events
//this is the most complex part of smooth operation
//flags starting, waking, and finishing


//When we finish, send one more start back to mediator which flags Should back to true to catch next screen off
public class Lockscreen extends Activity {
        
        //private ShakeListener mShaker;
		//for now shake is too inconsistent to really use. 
        
		Handler serviceHandler;
		Task myTask = new Task();
	
        private int timeoutpref = 15;        
        
        public int timeleft = 0;
        
        
/* Lifecycle flags */
        public boolean starting = true;
        public boolean waking = false;//any time quiet or active wake are up
        public boolean finishing = false;
        
        public boolean paused = false;
        
        public boolean shouldFinish = false;
        //flag it to true if user hits power to wake up but quiet wake was already active
        //this lets our task wait a half second, then actually wake up and finish
        
        public boolean screenwake = false;//set true when a wakeup key turns screen on
        
        public boolean resumedwithfocus = false;

        //very very complicated business.
        @Override
    protected void onCreate(Bundle icicle) {
        super.onCreate(icicle);

        
        requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD);
        		//| WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
		//| WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); //would behave like wakelock
        //this would stop the re-sleeps we get when actual cpu is trying to re-sleep. 
        //so screen off after that could be treated as shouldFinish always
        //how to handle re-sleep of the woken lockscreen?
        
        //use the task. we would have to introduce abort flags in touch events, much more complicated user activity detections
        
        /*    | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED);
         * this flag pushes the activity up and gets it ready at screen off but lockscreen rules stay in effect
         * that means this flag is good for showing a custom lockscreen to user (LIKE AN SMS POPUP)
         * it would have to be paired with the current full exit method after that (disable, pause, securely exit)
         * because after finish the lockscreen is still there
         * we also can't mediate key events with this one because lockscreen rules are still in control
         */
        
        /*
         * with dismiss keyguard flag, every key wakes phone after that (no lockscreen rules)
         * this example has vol and camera focus locked down
         * the power key will always instant unlock
         * and a camera full press will display the custom lockscreen!
         * back can be pressed while the screen is showing to unlock
         * 
         * how we accomplish this is by setting the window's brightness to 0.0
         * this is screen off, so it stays off even if the CPU is actually waking when vol is pressed
        */
        
        updateLayout();
        
        setBright((float) 0.0);
        
        //ensures that the window will keep screen off
        //we will deliberately turn it on when a wakeup key occurs
        //TESTING whether root cause of bug has to do with OS handling of our forced screen off state
        
        takeKeyEvents(true);
        //FORCE OUR STUPID KEY EVENTS TO GET HANDLED EVEN IF NO FOCUS!!
        //ANDRO-A.D.D.
        getWindow().takeKeyEvents(true);//see if forcing the window also helps consistency
        //this still has inconsistency. when locked on top of certain apps with input windows, we sometimes fail to react to first key event
        //the failure actually appears to be that we start to wake, but that the screen off broadcast is occurring and should not be
        
        IntentFilter offfilter = new IntentFilter (Intent.ACTION_SCREEN_OFF);
		registerReceiver(screenoff, offfilter);
        
        serviceHandler = new Handler();
        
        
        //TODO -- move this to the mediator
      //retrieve the user's normal timeout setting - SCREEN_OFF_TIMEOUT
    	/*try {
            timeoutpref = android.provider.Settings.System.getInt(getContentResolver(), android.provider.Settings.System.SCREEN_OFF_TIMEOUT);
    } catch (SettingNotFoundException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
    }//this setting will be restored at finish
    
    //Next, change the setting to 0 seconds
    android.provider.Settings.System.putInt(getContentResolver(), 
            android.provider.Settings.System.SCREEN_OFF_TIMEOUT, 0);*/
    //the device behavior ends up as just over 5 seconds when we do this.
    //when we set 1 here, it comes out 6.5 to 7 seconds between timeouts.
    
    /*registering the shake listener
    mShaker = new ShakeListener(this);
    mShaker.setOnShakeListener(new ShakeListener.OnShakeListener () {
      public void onShake()
      {
        wakeup();//try waking up in response to the shake
      }
    });*/
    //try acquiring the minimum partial wakelock to see if it allows us to catch shakes

        }
        
    protected View inflateView(LayoutInflater inflater) {
        return inflater.inflate(R.layout.lockactivity, null);
    }

    private void updateLayout() {
        LayoutInflater inflater = LayoutInflater.from(this);

        setContentView(inflateView(inflater));
    }
        
        
        @Override
    public void onBackPressed() {
        	if (screenwake) {
        		finishing = true;
        		setBright((float) 0.1);
        		moveTaskToBack(true);//finish();
        	}
        	//2.1 has a PM function is screen on
        	
        	//if screen is on we allow back to call finish. otherwise it does nothing
        	//a user can press back after cpu wake from a locked key, but nothing happens
        return;
    }
        
        //FIXME we have a bug where if the alarm clock goes off we get power key but we don't have any of our state flags set
        //when the alarm is created it gets keep screen on, so power presses merely cause screen on and off receivers to happen
        
        //to fix this we can get potentially check in onresume or on screen on
        //we aren't getting resumed but we lose focus, that's it. same reaction as if notif bar is pulled
        //we're losing focus but not aware of any wakes, so we should do a wakeup
        
    
    BroadcastReceiver screenoff = new BroadcastReceiver() {
        //we have to use screen off to set bright back to 0.0 so that true screen turn on is avoided for locked input
    	
    	//the OS is still going to call this as it is only our activity specifying the screen is off
    	//the OS still runs the flags that would make it be on for all other activities.
    	
        public static final String Screenoff = "android.intent.action.SCREEN_OFF";

        @Override
        public void onReceive(Context context, Intent intent) {
                if (!intent.getAction().equals(Screenoff)) return;
                
                
        /*if (starting) {
        	//this case is the screen off following Stop. Next start would normally come when mediator does startLock
        	finish();
        }*/
                
        //if a wakeup key had turned screen on, undo
        if (screenwake && hasWindowFocus()) {
        	//check for focus because another activity may still be waiting to return focus to us
        	//like a handcent popup ---- this works awesome
        	screenwake = false;
        	setBright((float) 0.0);
        	}
        else if (waking) {
        	//no screen wake exists but waking was set by the silent wake handling
        	//Real sleep is happening after a quiet wake
        	//either by timeout (when not in stay awake mode) or user is pressing power expecting wake
        	//by the time 5 seconds since last quiet wake pass we flag this false again in the task

        	shouldFinish=true;
        	
			
        	//but we actually need to wait a half second then call wakeup and finish.
        	//that's done by the task when this should flag is true;
        	}
        
        waking = false; //reset lifecycle
 
        
        return;//avoid unresponsive receiver error outcome
             
}};
    
    public void setBright(float value) {
    	Window mywindow = getWindow();
    	
    	WindowManager.LayoutParams lp = mywindow.getAttributes();

		lp.screenBrightness = value;

		mywindow.setAttributes(lp);
    }
    
    //call this task to turn off the screen in a fadeout.
    //i don't use it now, only used to test this method before coding the rest.
    //currently i just set our bright to 0 at oncreate instead.

    /*
    class Task implements Runnable {
    	public void run() {                
    		if (bright != 0) {
    			setBright(bright/100); //start at 10% bright and go to 0 (screen off)

    			bright--;
    			serviceHandler.postDelayed(myTask, 100L);
                }
    		else {
    			setBright((float) 0.0); 
    			
    			bright = 10;//put bright back
    		}
    	}
    }
    */
    
     //the timer isn't necessary when we use the screen wakelock (to avoid the flicker off bug)
    //it won't do a sleep again on its own so all we need to do is treat sleep as power key use
    	class Task implements Runnable {
        	public void run() {                
        		//the task will turn off the quiet wake 5 seconds after the button press happened
        		//essentially if a 2nd press happens before the 5 seconds is up we need to restart
        		//since that's what the real timeout does
        		
        		//best way is to actually use an int that does decrement every half sec
        		//timeleft is equal to 10 half-second ticks. when it gets to 0 then the flag is cleared
        		//when repeat calls happen we just put the int back at 5 sec worth (10 ticks)
        		if (shouldFinish) {
        			finishing=true;
        			
        			PowerManager myPM = (PowerManager) getApplicationContext().getSystemService(Context.POWER_SERVICE);
         	  	  	myPM.userActivity(SystemClock.uptimeMillis(), false);
         	  	  	Log.v("silent wake task","power sleep occurred, forcing wake and exit");
        			//wakeup();
        			setBright((float) 0.1); //tried moving this to the step that sets this flag and posts the delay
        			moveTaskToBack(true);//finish();
        		}
        		else if (timeleft!=0) {
        			timeleft--;
        			serviceHandler.postDelayed(myTask,500L);//just decrement every half second
        		}
        		/*else if (resumedwithfocus && !waking) {//resume with focus will call the task after 1 sec
        			if (!finishing) {//so if no wake is known and also no finish, do finish
        				finishing=true;
            			
            			PowerManager myPM = (PowerManager) getApplicationContext().getSystemService(Context.POWER_SERVICE);
             	  	  	myPM.userActivity(SystemClock.uptimeMillis(), false);
             	  	  	Log.v("missed key event catch","we got resumed, had focus, and did not exit within 1 sec");
            			//wakeup();
            			setBright((float) 0.1); //tried moving this to the step that sets this flag and posts the delay
            			moveTaskToBack(true);//finish();
        			}
        		}*/
        		
        		else if (!screenwake) {
        			waking = false;//no more wake flags unless the screen wake has cancelled the silent wake
        		}
        	}
        	//this workaround is only relevant to power key which we can't prevent from causing the go to sleep if any wake exists
        	//this is the case during the 5 seconds in regular or always after a locked key in stay awake mode.
        	//FIXME seems like not a good idea to leave that wake.. 
        	//have the stay awake disengage once we know lock activity started, re-engage at exit callback
        	
        	
    }
    
    public void wakeup() {
    	setBright((float) 0.1);//tell screen to go on with 10% brightness
    	//poke user activity just to be safe that it won't flicker back off
    	PowerManager myPM = (PowerManager) getApplicationContext().getSystemService(Context.POWER_SERVICE);
  	  	myPM.userActivity(SystemClock.uptimeMillis(), false);
  	  	
    	screenwake = true;
    	timeleft = 0;//this way the task doesn't keep going
    	
    	    	  	  	
  	  	
    }
    
    
    
    //TODO --- make stay awake capable of activating when slide is opened
    @Override
    public void onConfigurationChanged(Configuration newConfig) {
    	super.onConfigurationChanged(newConfig);
     	if (newConfig.hardKeyboardHidden == Configuration.HARDKEYBOARDHIDDEN_NO) {
     		//this means that a config change happened and the keyboard is open
     		finishing = true;
     		//wakeup();
     		setBright((float) 0.1);
        	moveTaskToBack(true);//finish();
      	  	//let's instant unlock when slide open
     	}
     	
     		//we will just do nothing if a config change comes and the hard keyboard is hidden
     		//we'll let sleep handle itself
     	
    	/*A flag indicating whether the hard keyboard has been hidden.
    	This will be set on a device with a mechanism to hide the keyboard from the user, when that mechanism is closed.
    	One of: HARDKEYBOARDHIDDEN_NO, HARDKEYBOARDHIDDEN_YES.
    	*/

//we could do something in response to a rotation but we have declared portrait only in manifest
//we receive the orientation config change only to ensure we aren't destroyed and recreated at time of change
    	
    }
        
    @Override
    protected void onStop() {
        super.onStop();
                
        Log.v("lockscreen stop","checking if user left");
        if (finishing) {
        	Log.v("lock stop","onStop is telling mediator we have been unlocked by one touch unlock");
        }
        else if (screenwake && paused && !hasWindowFocus()) {
        	//we were awake, we got paused, and lost focus
        	//this only happens if user is navigating out via notif or home key shortcuts
        	Log.v("lock stop","onStop is telling mediator we have been unlocked by user navigation");
        	
        }
        else return;//I can't think of a stop that wouldn't be one of these two
        
        
        
        starting = true;//this way if we get brought back we'll be aware of it
        resumedwithfocus = false;

        CallbackMediator();
        //this works. it tells the service that start lock needs to be done
        //next screen off, the Lock activity sleeping in the background is reset to started
       //FIXME looks like we need to still wait a short time then destroy the activity
        //otherwise it is possible for user to get back in to it in an usable state by navigating in via back key presses
    }
    
    @Override
    protected void onPause() {
    	super.onPause();
    	//appears we always pause when leaving the lockscreen but it also happens at times in sleep and wakeup
    	//we don't have to destroy, as create can reset the lifecycle
    	//we can send the second mediator callback when user leaves just as if they did instant unlock destroy
    	Log.v("lock paused","setting pause flag");
    	
    	//since pauses also occur while it is asleep but focus is not lost, we will only send "exited" callback
    	//when paused and !hasWindowFocus()
    	paused = true;
    	resumedwithfocus = false;
    }
    
    @Override
    protected void onResume() {
    	super.onResume();
    	Log.v("lock resume","setting pause flag");
    	paused = false;
    	//if we get a resume, and we have focus, and no state flags yet, this means we are getting a first key down
    	//key event does not get this initial thing because apparently it is getting eaten by the wakeup action
    	if (hasWindowFocus()) {
    		resumedwithfocus = true;
    		//serviceHandler.postDelayed(myTask, 1000L);
    	}
    }
    
    @Override
    public void onDestroy() {
        super.onDestroy();
        
        //TODO move this to the mediator callback handling for finished lock activity
      //restore the users preference for timeout so that the screen will sleep as they expect
        /*
		android.provider.Settings.System.putInt(getContentResolver(),
				android.provider.Settings.System.SCREEN_OFF_TIMEOUT, timeoutpref);
		//then send a new userActivity call to the power manager
		PowerManager pm = (PowerManager) getSystemService (Context.POWER_SERVICE); 
    	pm.userActivity(SystemClock.uptimeMillis(), false);*/
        
       serviceHandler.removeCallbacks(myTask);
       serviceHandler = null;
       
       unregisterReceiver(screenoff);
      
       //CallbackMediator();
       //this will have already been done by stop logic
    	
        Log.v("destroyWelcome","Destroying");
    }
        
    //public void takeKeyEvents (boolean get)
    //Request that key events come to this activity.
    //Use this if your activity has no views with focus
    //but still want a chance to process key events.
    
    @Override
    public void onWindowFocusChanged (boolean hasFocus) {
    	if (hasFocus) {
    		//do nothing
    		Log.v("focus change","we have gained focus");
    		//Catch first focus gain after onStart here.
    		//this allows us to know if we actually got as far as having focus (expected but bug sometimes prevents
    		if (starting) {
    			starting = false;//set our own lifecycle reference now that we know we started and got focus properly
    			CallbackMediator();
    		}
    		else if(!waking && !finishing) {
    			//this essentially means we failed to get focus at off and it was given to us at screen on instead
    			//focus changes should not be coming in while it is off and dormant
    			//the flags here can only be set when we had focus.
    			Log.v("unhandled wake exception","forcing exit due to wakeup that fell on the floor");
    			finishing = true;
    			setBright((float) 0.1);
    			moveTaskToBack(true);
    		}
    	}
    	else {    		    		   		
    		//if (!hasWindowFocus()) //seems to return same thing as this event reaction method
    			Log.v("focus loss","lost focus, checking if we should wake to yield to other events");
    			if (!waking && !finishing && paused) {
    				//not aware of any deliberate action- we're paused, not awake, and not about to finish
    				//this focus loss means something else needs us to wake up the screen (like a ringing alarm)
    				waking=true;
    				wakeup();
    				//this passes a wakeup we don't cancel unless we see that we have focus again
    			}
    			// if (screenwake) finish();
    		
    	
    	}
    }
    
    protected void onStart() {
    	super.onStart();
    	Log.v("lockscreen start success","setting flags");
    	
    	if (finishing) {
    		finishing = false;//since we are sometimes being brought back, safe to ensure flags are like at creation
    		shouldFinish = false;
    		waking = false;
    		setBright((float) 0.0);
    	}
    	//takeKeyEvents(true);
        //getWindow().takeKeyEvents(true);
    }
    
    public void CallbackMediator() {
        Intent i = new Intent();
    	i.setClassName("i4nc4mp.myLock", "i4nc4mp.myLock.CustomLockService");
    	startService(i);
        }
    
    /* I can't seem to get the camera long press. The camera starts instead.. haha
    @Override
    public boolean onKeyLongPress(int keyCode, KeyEvent event) {
    if (keyCode==KeyEvent.KEYCODE_CAMERA) {
			if (!finishing && !waking) {//test for camera long press during sleep to instant unlock
			Log.v("long press","camera long unlock");
			
			//how to make haptic?
    		finishing = true;
    		setBright((float) 0.1);
    		//wakeup();
    		moveTaskToBack(true);//finish();
    		
    		
    		
    	}
    	return true;
    }
    else return false;
    }*/
    
    //here's where most of the magic happens
    // ----- we actually can't get a key down from sleep state
    //that event causes a resume and we will have focus already when it happens.
    //so we have to handle up if we want to get shit done here
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        // Do this on key down
        boolean up = event.getAction() == KeyEvent.ACTION_UP;
        //flags to true if the event we are getting is the up (release)
        int code = event.getKeyCode();
        //for next alpha release I am not going to have a wakeup lockscreen, until I have slide to unlock implemented
       if (code == KeyEvent.KEYCODE_POWER) {//|| code == KeyEvent.KEYCODE_CAMERA || 
       //wakeup lockscreen on up --- first of all I am testing as in the alpha 2b method using a short delay before we Stop
    	   //this way down can be handled first in case of long press
    	   /*if (up && !screenwake) {
                   waking = true;
                  	Log.v("key event","wake key");
               	wakeup();
    	   }*/
    	   //if (!up) Log.v("power key","we can get power key down... *~*");
    	   if (up && !finishing) {
    		   //shouldFinish = true;
    		   Log.v("unlock key","power key UP, unlocking");
    		   finishing = true;
    		   PowerManager myPM = (PowerManager) getApplicationContext().getSystemService(Context.POWER_SERVICE);
    	  	  	myPM.userActivity(SystemClock.uptimeMillis(), false);//since the error seems to be the fact it is flickering off,
    	  	  	
    		   setBright((float) 0.1);
    		   
    		  
    		   //serviceHandler.postDelayed(myTask, 50L);
    		   moveTaskToBack(true);
    		  
    	   }
                   return true;
       }
       else {//if (code != KeyEvent.KEYCODE_CAMERA){//locked all other keys but camera which will come thru in long press
    	   
    	   //if (!up) Log.v("locked key","we can get a non power key down");
    	   
    	   if (!screenwake && up) {
         	   timeleft=10;//so that countdown is refreshed
            //countdown won't be running in screenwakes
         	if (!waking) {
            //start up the quiet wake timer    
             Log.v("key event","locked key timer starting");

             	waking = true;
             	serviceHandler.postDelayed(myTask, 500L);
             		}
            }//this seems to be broken if i allow it to fully run... it worked on the 5 sec timeout
    	   //when it runs this long, the screen inexplicably lights up again, then when it sleeps the power key code runs
           //trying counting 10 secs of the 15
    	   //still inexplicably goes bright, but now the timer is done so we aren't getting the wrong shouldFinish logic
            //definitely seems to be the 11th second that bright happens
    	   
    	   //probably need to go back to the timeout adjustment and get it integrated in the lifecycle
    	   //just at the mediator start and stop callbacls
             
             return true;
       }
       //else return false;
        
        //switch (code) {
            //case KeyEvent.KEYCODE_VOLUME_UP:
            //case KeyEvent.KEYCODE_VOLUME_DOWN:
            //case KeyEvent.KEYCODE_FOCUS:
            //case KeyEvent.KEYCODE_POWER:
               //moved locked down logic to default
            	
                
               
            //case KeyEvent.KEYCODE_VOLUME_UP:
            //case KeyEvent.KEYCODE_VOLUME_DOWN:
            /*case KeyEvent.KEYCODE_CAMERA:
            	if (!screenwake && up) {
                waking = true;
               	Log.v("key event","wake key");
            	wakeup();
            	
                return true;
            	}
            	else return false;*/
            	
               	
                
               
               
            /*case KeyEvent.KEYCODE_POWER:
 				if (!finishing) {
            		
            		finishing = true;
            		
            		//wakeup();
            		moveTaskToBack(true);//finish();
            		
            		
            		Log.v("key event","unlock key, commence send self to back");
            	}
            	return false;//allow this key to register with the system in a way that will stabilize the wakeup
            	*/
            //default:
            	/*if (!finishing) {
            		
            		finishing = true;
            		
            		//wakeup();
            		moveTaskToBack(true);//finish();
            		
            		
            		Log.v("key event","unlock key, commence send self to back");*/
            	
                
                
            	}
    
            	//TODO if a screen wakeup finishes and no wake flags are true (no screen wake and no CPU wake)
            	//we can handle that too in a screen on receiver, by checking if we have focus
    //otherwords an unexpected wake where nothing stole focus (unhandled wake needs to cause exit also)
            	   
            	//the only case we don't get this event is during a silent wake
            	//when user presses power
            	//timeleft & the task handle this case
        
    
}