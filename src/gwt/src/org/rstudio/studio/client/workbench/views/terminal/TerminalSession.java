/*
 * TerminalSession.java
 *
 * Copyright (C) 2009-16 by RStudio, Inc.
 *
 * Unless you have received this program directly from RStudio pursuant
 * to the terms of a commercial license agreement with RStudio, then
 * this program is licensed to you under the terms of version 3 of the
 * GNU Affero General Public License. This program is distributed WITHOUT
 * ANY EXPRESS OR IMPLIED WARRANTY, INCLUDING THOSE OF NON-INFRINGEMENT,
 * MERCHANTABILITY OR FITNESS FOR A PARTICULAR PURPOSE. Please refer to the
 * AGPL (http://www.gnu.org/licenses/agpl-3.0.txt) for more details.
 *
 */

package org.rstudio.studio.client.workbench.views.terminal;

import org.rstudio.core.client.CommandWithArg;
import org.rstudio.core.client.HandlerRegistrations;
import org.rstudio.studio.client.RStudioGinjector;
import org.rstudio.studio.client.common.console.ConsoleOutputEvent;
import org.rstudio.studio.client.common.console.ConsoleProcess;
import org.rstudio.studio.client.common.console.ConsoleProcessInfo;
import org.rstudio.studio.client.common.console.ProcessExitEvent;
import org.rstudio.studio.client.common.shell.ShellInput;
import org.rstudio.studio.client.common.shell.ShellSecureInput;
import org.rstudio.studio.client.server.ServerError;
import org.rstudio.studio.client.server.ServerRequestCallback;
import org.rstudio.studio.client.server.Void;
import org.rstudio.studio.client.server.VoidServerRequestCallback;
import org.rstudio.studio.client.workbench.model.WorkbenchServerOperations;
import org.rstudio.studio.client.workbench.views.terminal.events.ResizeTerminalEvent;
import org.rstudio.studio.client.workbench.views.terminal.events.TerminalDataInputEvent;
import org.rstudio.studio.client.workbench.views.terminal.events.TerminalSessionStartedEvent;
import org.rstudio.studio.client.workbench.views.terminal.events.TerminalSessionStoppedEvent;
import org.rstudio.studio.client.workbench.views.terminal.xterm.XTermWidget;

import com.google.gwt.event.shared.HandlerRegistration;
import com.google.inject.Inject;


/**
 * A connected Terminal session.
 */
public class TerminalSession extends XTermWidget
                             implements ConsoleOutputEvent.Handler, 
                                        ProcessExitEvent.Handler,
                                        ResizeTerminalEvent.Handler,
                                        TerminalDataInputEvent.Handler,
                                        TerminalSessionStartedEvent.HasHandlers,
                                        TerminalSessionStoppedEvent.HasHandlers
{
   public TerminalSession(final ShellSecureInput secureInput)
   {
      RStudioGinjector.INSTANCE.injectMembers(this);
      secureInput_ = secureInput;
      setHeight("100%");
   }
   
   @Inject
   private void initialize(WorkbenchServerOperations server)
   {
      server_ = server;
   }
   
   /**
    * Create a terminal process and connect to it.
    */
   public void connect()
   {
      server_.startShellDialog(ConsoleProcess.TerminalType.XTERM, 
                               80, 25,
                               false, /* not a modal dialog */
                               new ServerRequestCallback<ConsoleProcess>()
      {
         @Override
         public void onResponseReceived(ConsoleProcess consoleProcess)
         {
            consoleProcess_ = consoleProcess;
            
            if (getInteractionMode() != ConsoleProcessInfo.INTERACTION_ALWAYS)
            {
               writeError("Unsupported ConsoleProcess interaction mode");
               return;
            } 

            if (consoleProcess_ != null)
            {
               addHandlerRegistration(consoleProcess_.addConsoleOutputHandler(TerminalSession.this));
               addHandlerRegistration(consoleProcess_.addProcessExitHandler(TerminalSession.this));
               addHandlerRegistration(addResizeTerminalHandler(TerminalSession.this));
               addHandlerRegistration(addTerminalDataInputHandler(TerminalSession.this));

               consoleProcess.start(new ServerRequestCallback<Void>()
               {
                  @Override
                  public void onResponseReceived(Void response)
                  {
                     fireEvent(new TerminalSessionStartedEvent(terminalTitle_, TerminalSession.this));
                  }
                  
                  @Override
                  public void onError(ServerError error)
                  {
                     writeError(error.getUserMessage());
                  }
               });
            }
         }
      
         @Override
         public void onError(ServerError error)
         {
            writeError(error.getUserMessage());
         }
         
      });
   }
   
   @Override
   public void onConsoleOutput(ConsoleOutputEvent event)
   {
      write(event.getOutput());
   }
   
   @Override
   public void onProcessExit(ProcessExitEvent event)
   {
      unregisterHandlers();
      if (consoleProcess_ != null)
      {
         consoleProcess_.reap(new VoidServerRequestCallback());
      }
     
      consoleProcess_ = null;
      fireEvent(new TerminalSessionStoppedEvent(terminalTitle_, this));
   }

   
   @Override
   public void onResizeTerminal(ResizeTerminalEvent event)
   {
      consoleProcess_.resizeTerminal(
            event.getCols(), event.getRows(),
            new VoidServerRequestCallback() 
            {
               @Override
               public void onError(ServerError error)
               {
                  writeln(error.getUserMessage());
               }
            });
   }
   
   @Override
   public void onTerminalDataInput(TerminalDataInputEvent event)
   {
      secureInput_.secureString(event.getData(), new CommandWithArg<String>() 
      {
         @Override
         public void execute(String arg) // success
         {
            consoleProcess_.writeStandardInput(
               ShellInput.create(arg,  true /* echo input*/), 
               new VoidServerRequestCallback() {
                  @Override
                  public void onError(ServerError error)
                  {
                     writeln(error.getUserMessage());
                  }
               });
          }
      },
      new CommandWithArg<String>()
      {
         @Override
         public void execute(String errorMessage) // failure
         {
            writeln(errorMessage); 
         }
      });
   }
   
   private int getInteractionMode()
   {
      if (consoleProcess_ != null)
         return consoleProcess_.getProcessInfo().getInteractionMode();
      else
         return ConsoleProcessInfo.INTERACTION_NEVER;
   } 

   protected void addHandlerRegistration(HandlerRegistration reg)
   {
      registrations_.add(reg);
   }
   
   protected void unregisterHandlers()
   {
      registrations_.removeHandler();
   }

   protected void writeError(String msg)
   {
      write(AnsiColor.RED +"Fatal Error: " + msg);
   }

   @Override
   public void onUnload()
   {
      super.onUnload();
      unregisterHandlers();
   }
   
   public String getTerminalTitle()
   {
      return terminalTitle_;
   }

   @Override
   public HandlerRegistration addTerminalSessionStartedHandler(TerminalSessionStartedEvent.Handler handler)
   {
      return handlers_.addHandler(TerminalSessionStartedEvent.TYPE, handler);
   }

   @Override
   public HandlerRegistration addTerminalSessionStoppedHandler(TerminalSessionStoppedEvent.Handler handler)
   {
      return handlers_.addHandler(TerminalSessionStoppedEvent.TYPE,  handler);
   }
   
   private final ShellSecureInput secureInput_;
   private HandlerRegistrations registrations_ = new HandlerRegistrations();
   private ConsoleProcess consoleProcess_;
   private String terminalTitle_ = "(Not Connected)"; 
   
   // Injected ---- 
   private WorkbenchServerOperations server_; 
}
