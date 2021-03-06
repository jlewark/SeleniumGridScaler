/*
 * Copyright (C) 2014 RetailMeNot, Inc.
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 */
package com.rmn.qa.task;

import java.util.Date;
import java.util.Iterator;
import java.util.Map;

import org.openqa.grid.internal.ProxySet;
import org.openqa.selenium.Platform;
import org.openqa.selenium.remote.CapabilityType;
import org.openqa.selenium.remote.DesiredCapabilities;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Maps;
import com.rmn.qa.AutomationContext;
import com.rmn.qa.AutomationDynamicNode;
import com.rmn.qa.AutomationUtils;
import com.rmn.qa.BrowserPlatformPair;
import com.rmn.qa.NodesCouldNotBeStartedException;
import com.rmn.qa.RegistryRetriever;
import com.rmn.qa.aws.VmManager;
import com.rmn.qa.servlet.AutomationTestRunServlet;

/**
 * Registry task which registers unregistered dynamic {@link AutomationDynamicNode nodes}.  This can happen if the hub process restarts for whatever reason
 * and loses track of previously registered nodes
 * @author mhardin
 */
public class AutomationScaleNodeTask extends AbstractAutomationCleanupTask {

    private static final Logger log = LoggerFactory.getLogger(AutomationScaleNodeTask.class);
    private Map<BrowserPlatformPair, Date> queuedBrowsersPlatforms = Maps.newHashMap();
    private VmManager vmManager;
    // Map to maintain when a node was pending to enforce timeout logic if the node never comes up
    @VisibleForTesting
    Map<String,Date> nodeToCreation = Maps.newHashMap();
    @VisibleForTesting
    static final String NAME = "Automation Scale Node Task";

    /**
     * Constructs a registry task with the specified context retrieval mechanism
     * @param registryRetriever Represents the retrieval mechanism you wish to use
     */
    public AutomationScaleNodeTask(RegistryRetriever registryRetriever, VmManager vmManager) {
        super(registryRetriever);
        this.vmManager = vmManager;
    }

    /**
     * Returns the ProxySet to be used for cleanup purposes.
     * @return
     */
    protected ProxySet getProxySet() {
        return registryRetriever.retrieveRegistry().getAllProxies();
    }

    @Override
    public String getDescription() {
        return AutomationScaleNodeTask.NAME;
    }

    @VisibleForTesting
    Iterable<DesiredCapabilities> getDesiredCapabilities() {
        return registryRetriever.retrieveRegistry().getDesiredCapabilities();
    }

    @VisibleForTesting
    void startNodes(VmManager vmManager, int browsersToStart, String browser, Platform platform) throws NodesCouldNotBeStartedException {
        AutomationTestRunServlet.startNodes(vmManager, "AD-HOC", browsersToStart, browser, platform);
    }

    /**
     * Returns true if nodePendingDate is more than 15 seconds older than the current time
     */
    @VisibleForTesting
    boolean isNodeOldEnoughToCreateNewNode(Date nowDate, Date nodePendingDate) {
        return nowDate.getTime() - nodePendingDate.getTime() > 15000;
    }

    /**
     * Returns true if the platform is not specified, is all platforms, or is linux
     * @param platformPair
     * @return
     */
    private boolean isEligibleToScale(BrowserPlatformPair platformPair) {
        return AutomationUtils.browserAndPlatformSupported(platformPair);
    }

    // We're going to continuously iterate over queued test requests with the hub.  If there are no nodes pending startup, we're basically going to calculate load
    // over time to see if we need to spin up new instances ad-hoc
    @Override
    public void doWork() {
        log.warn("Doing node scale work");
        if (AutomationContext.getContext().noPendingNodesExist()) {
            nodeToCreation.clear();
            Iterator<DesiredCapabilities> pendingCapabilities = getDesiredCapabilities().iterator();
            if (pendingCapabilities.hasNext()) {
                log.info("No nodes pending startup exist.  Analyzing currently queued requests.");
                while (pendingCapabilities.hasNext()) {
                    DesiredCapabilities capabilities = pendingCapabilities.next();
                    String browser = (String) capabilities.getCapability(CapabilityType.BROWSER_NAME);
                    Object platformObject = capabilities.getCapability(CapabilityType.PLATFORM);
                    Platform platform = AutomationUtils.getPlatformFromObject(platformObject);
                    // If a valid platform wasn't able to be parsed from the queued test request, go ahead and default to Platform.ANY,
                    // as Platform is not required for this plugin
                    if (platform == null) {
                        // Default to ANY here and let AwsVmManager dictate what ANY translates to
                        platform = Platform.ANY;
                    }
                    // Group all platforms by their underlying family
                    platform = AutomationUtils.getUnderlyingFamily(platform);
                    BrowserPlatformPair desiredPair = new BrowserPlatformPair(browser, platform);

                    // Don't attempt to calculate load for browsers & platform families we cannot start
                    if (!isEligibleToScale(desiredPair)) {
                        log.warn("Unsupported browser and platform pair, browser:  " + browser + " platform family: " + platform.family());
                        continue;
                    }

                    // Handle requests for specific browser platforms.
                    queuedBrowsersPlatforms.computeIfAbsent(new BrowserPlatformPair(browser, platform), s -> new Date());
                }
            }
        } else {
            log.warn("Nodes pending startup still exist, skipping");
            Iterator<String> iterator = AutomationContext.getContext().getPendingStartupNodes().iterator();
            while (iterator.hasNext()) {
                String node = iterator.next();
                if (!nodeToCreation.containsKey(node)) {
                    nodeToCreation.put(node, new Date());
                    log.warn(String.format("Pending node %s found for the first time.  Adding", node));
                } else {
                    Date createdDate = nodeToCreation.get(node);
                    // If a node has been pending for over 10 minutes, its probably never going to come online.
                    // Stop tracking it so we don't get hung up indefinitely waiting for this node to come online
                    if ((System.currentTimeMillis() - createdDate.getTime()) > 600000) { // 10 minutes
                        log.error(String.format("Node %s was pending longer than 10 minutes.  Removing.", node));
                        iterator.remove();
                        nodeToCreation.remove(node);
                        continue;
                    }
                }
                log.warn("Node pending startup: " + node);
            }
            // Clear out all queued browsers as we need to reset the logic if nodes are still coming online
            queuedBrowsersPlatforms.clear();
        }
        Iterator<BrowserPlatformPair> iterator = queuedBrowsersPlatforms.keySet().iterator();
        while(iterator.hasNext()) {
            BrowserPlatformPair browserPlatform = iterator.next();
            Date currentTime = new Date();
            Date timeBrowserPlatformQueued = queuedBrowsersPlatforms.get(browserPlatform);
            if (isNodeOldEnoughToCreateNewNode(currentTime, timeBrowserPlatformQueued)) { // If we've had pending queued requests for this browser for at least 15 seconds
                Iterator<DesiredCapabilities> pendingCapabilities = getDesiredCapabilities().iterator();
                if (pendingCapabilities.hasNext()) {
                    int browsersToStart = 0;
                    while (pendingCapabilities.hasNext()) {
                        DesiredCapabilities capabilities = pendingCapabilities.next();
                        String queuedBrowser = (String) capabilities.getCapability(CapabilityType.BROWSER_NAME);
                        Object platformObject = capabilities.getCapability(CapabilityType.PLATFORM);
                        Platform queuedPlatform = AutomationUtils.getPlatformFromObject(platformObject);
                        // If a valid platform wasn't able to be parsed from the queued test request, go ahead and default to Platform.ANY,
                        // as Platform is not required for this plugin
                        if (queuedPlatform == null) {
                            queuedPlatform = Platform.ANY;
                        }
                        // Group all platforms by their underlying family
                        queuedPlatform = AutomationUtils.getUnderlyingFamily(queuedPlatform);
                        if (browserPlatform.equals(new BrowserPlatformPair(queuedBrowser, queuedPlatform))) {
                            browsersToStart++;
                        }
                    }
                    if (browsersToStart > 0) {
                        log.info(String.format("Spinning up %d threads for browser %s based on current test load", browsersToStart, browserPlatform.getBrowser()));
                        try {
                            this.startNodes(vmManager, browsersToStart, browserPlatform.getBrowser(), browserPlatform.getPlatform());
                        } catch (NodesCouldNotBeStartedException e) {
                            throw new RuntimeException("Error scaling up nodes", e);
                        }
                    }
                }
                // Regardless of if we spun up browsers or not, clear this count out
                iterator.remove();
            }
        }
    }
}