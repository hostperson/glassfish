/*
 * The contents of this file are subject to the terms 
 * of the Common Development and Distribution License 
 * (the License).  You may not use this file except in
 * compliance with the License.
 * 
 * You can obtain a copy of the license at 
 * https://glassfish.dev.java.net/public/CDDLv1.0.html or
 * glassfish/bootstrap/legal/CDDLv1.0.txt.
 * See the License for the specific language governing 
 * permissions and limitations under the License.
 * 
 * When distributing Covered Code, include this CDDL 
 * Header Notice in each file and include the License file 
 * at glassfish/bootstrap/legal/CDDLv1.0.txt.  
 * If applicable, add the following below the CDDL Header, 
 * with the fields enclosed by brackets [] replaced by
 * you own identifying information: 
 * "Portions Copyrighted [year] [name of copyright owner]"
 * 
 * Copyright 2006 Sun Microsystems, Inc. All rights reserved.
 */

package org.glassfish.ejb.startup;

import com.sun.ejb.Container;
import com.sun.ejb.ContainerFactory;
import com.sun.ejb.containers.AbstractSingletonContainer;
import com.sun.enterprise.deployment.EjbDescriptor;

// For auto-deploying EJBTimerService
import com.sun.ejb.containers.EjbContainerUtil;
import com.sun.ejb.containers.EjbContainerUtilImpl;
import com.sun.ejb.containers.EJBTimerService;
import com.sun.enterprise.v3.common.PlainTextActionReporter;
import com.sun.enterprise.v3.admin.CommandRunner;
import org.glassfish.api.ActionReport;
import org.glassfish.internal.api.ServerContext;
import org.glassfish.internal.deployment.Deployment;

import java.io.File;
import java.util.Properties;
import java.util.logging.Logger;
import java.util.logging.Level;
// For auto-deploying EJBTimerService

import org.glassfish.api.deployment.ApplicationContainer;
import org.glassfish.api.deployment.ApplicationContext;
import org.glassfish.api.deployment.DeploymentContext;
import org.glassfish.ejb.security.application.EJBSecurityManager;
import org.glassfish.ejb.security.factory.EJBSecurityManagerFactory;
import org.jvnet.hk2.annotations.Inject;
import org.jvnet.hk2.annotations.Scoped;
import org.jvnet.hk2.annotations.Service;
import org.jvnet.hk2.component.Habitat;
import org.jvnet.hk2.component.PerLookup;

import java.util.ArrayList;
import java.util.Collection;

/**
 * Ejb container service
 *
 * @author Mahesh Kannan
 */
@Service(name = "ejb")
@Scoped(PerLookup.class)
public class EjbApplication
        implements ApplicationContainer<Collection<EjbDescriptor>> {

    private String appName;
    private Collection<EjbDescriptor> ejbs;
    private Collection<Container> containers = new ArrayList();
    private ClassLoader ejbAppClassLoader;
    private DeploymentContext dc;
    
    private Habitat habitat;

    @Inject
    private EJBSecurityManagerFactory ejbSMF;

    private ContainerFactory ejbContainerFactory;

    private SingletonLifeCycleManager singletonLCM;

    // TODO: move restoreEJBTimers to correct location
    private static boolean restored = false;
    private static Object lock = new Object();
    // TODO: move restoreEJBTimers to correct location

    public EjbApplication(
            Collection<EjbDescriptor> bundleDesc, DeploymentContext dc,
            ClassLoader cl, Habitat habitat) {
        this.ejbs = bundleDesc;
        this.ejbAppClassLoader = cl;
        this.appName = ""; //TODO
        this.dc = dc;
        this.habitat = habitat;
        this.ejbContainerFactory = habitat.getByContract(ContainerFactory.class);
    }

    public Collection<EjbDescriptor> getDescriptor() {
        return ejbs;
    }

    public boolean start(ApplicationContext startupContext) {
        return true;
    }

    boolean loadAndStartContainers(ApplicationContext startupContext) {
        /*
        Set<EjbDescriptor> descs = (Set<GEjbDescriptor>) bundleDesc.getEjbs();

        long appUniqueID = ejbs.getUniqueId();
        long appUniqueID = 0;
        if (appUniqueID == 0) {
            appUniqueID = (System.currentTimeMillis() & 0xFFFFFFFF) << 16;
        }
        */

        //System.out.println("**CL => " + bundleDesc.getClassLoader());
        int counter = 0;
        boolean usesEJBTimerService = false;
        singletonLCM = new SingletonLifeCycleManager();

        for (EjbDescriptor desc : ejbs) {
            desc.setUniqueId(getUniqueId(desc)); // XXX appUniqueID + (counter++));

            try {
                EJBSecurityManager ejbSM = null;//ejbSMF.createManager(desc,true);
                Container container = ejbContainerFactory.createContainer(desc, ejbAppClassLoader,
                        ejbSM, dc);
                containers.add(container);
                if (container instanceof AbstractSingletonContainer) {
                    singletonLCM.addSingletonContainer((AbstractSingletonContainer) container);
                }
                usesEJBTimerService = (usesEJBTimerService || container.isTimedObject());
            } catch (Throwable th) {
                throw new RuntimeException("Error during EjbApplication.start() ", th);
            }
        }

        // TODO: move restoreEJBTimers to correct location
        System.out.println("==> Uses Timers? == " + usesEJBTimerService);
        if (usesEJBTimerService) {
            initEJBTimerService();
        }
        // TODO: move restoreEJBTimers to correct location

        for (Container container : containers) {
            container.doAfterApplicationDeploy();
        }

        singletonLCM.doStartup();
        
        return true;
    }

    public boolean stop(ApplicationContext stopContext) {
        // First, shutdown any singletons that were initialized based
        // on a particular ordering dependency.
        // TODO Make sure this covers both eagerly and lazily initialized
        // Singletons.
        singletonLCM.doShutdown();

        for (Container container : containers) {
            container.onShutdown();
        }
        
        return true;
    }

    /**
     * Suspends this application container.
     *
     * @return true if suspending was successful, false otherwise.
     */
    public boolean suspend() {
        // Not (yet) supported
        return false;
    }

    /**
     * Resumes this application container.
     *
     * @return true if resumption was successful, false otherwise.
     */
    public boolean resume() {
        // Not (yet) supported
        return false;
    }

    /**
     * Returns the class loader associated with this application
     *
     * @return ClassLoader for this app
     */
    public ClassLoader getClassLoader() {
        return ejbAppClassLoader;
    }

    private static final char NAME_PART_SEPARATOR = '_';   // NOI18N
    private static final char NAME_CONCATENATOR = ' ';   // NOI18N

    private long getUniqueId(EjbDescriptor desc) {

        com.sun.enterprise.deployment.BundleDescriptor bundle = desc.getEjbBundleDescriptor();
        com.sun.enterprise.deployment.Application application = bundle.getApplication();

        // Add ejb name and application name.
        StringBuffer rc = new StringBuffer().
                append(desc.getName()).
                append(NAME_CONCATENATOR).
                append(application.getRegistrationName());

        // If it's not just a module, add a module name.
        if (!application.isVirtual()) {
            rc.append(NAME_CONCATENATOR).
                    append(bundle.getModuleDescriptor().getArchiveUri());
        }

        return rc.toString().hashCode();
    }

    protected void undeploy() {
        for (Container container : containers) {
            container.undeploy();
        }
        containers.clear();
    }

    private void initEJBTimerService() {
        synchronized (lock) {
            EjbContainerUtil ejbContainerUtil = EjbContainerUtilImpl.getInstance();

            EJBTimerService ejbTimerService = ejbContainerUtil.getEJBTimerService();

            if (ejbTimerService == null) {

                Logger logger = ejbContainerUtil.getLogger();

                Deployment deployment = habitat.getByContract(Deployment.class);
                boolean isRegistered = deployment.isRegistered("ejb-timer-service-app");

                if (isRegistered) {
                    logger.log (Level.FINE, 
                            "EJBTimerService is already deployed and will be loaded later.");
                    return;
                }

                logger.log (Level.INFO, "Loading EJBTimerService. Please wait.");

                ServerContext sc = habitat.getByContract(ServerContext.class);
                File root = sc.getInstallRoot();
                File app = new File(root, 
                        "lib/install/applications/ejb-timer-service-app.war");

                Properties params = new Properties();
                params.put("path", app.getAbsolutePath()); 

                ActionReport report = new PlainTextActionReporter();
                CommandRunner cr = habitat.getComponent(CommandRunner.class);

                cr.doCommand("deploy", params, report);

                if (report.getActionExitCode() != ActionReport.ExitCode.SUCCESS) {
                    throw new RuntimeException("Failed to deploy EJBTimerService: " + 
                            report.getFailureCause());
                }
            }
        }
    }
}
