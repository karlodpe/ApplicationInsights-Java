/*
 * ApplicationInsights-Java
 * Copyright (c) Microsoft Corporation
 * All rights reserved.
 *
 * MIT License
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this
 * software and associated documentation files (the ""Software""), to deal in the Software
 * without restriction, including without limitation the rights to use, copy, modify, merge,
 * publish, distribute, sublicense, and/or sell copies of the Software, and to permit
 * persons to whom the Software is furnished to do so, subject to the following conditions:
 * The above copyright notice and this permission notice shall be included in all copies or
 * substantial portions of the Software.
 * THE SOFTWARE IS PROVIDED *AS IS*, WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED,
 * INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR
 * PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE
 * FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR
 * OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER
 * DEALINGS IN THE SOFTWARE.
 */
package com.microsoft.applicationinsights.agent.bootstrap;

import java.io.File;
import java.lang.instrument.Instrumentation;
import java.net.URL;
import java.nio.file.Path;

import com.microsoft.applicationinsights.agent.bootstrap.configuration.Configuration;
import com.microsoft.applicationinsights.agent.bootstrap.configuration.ConfigurationBuilder;
import com.microsoft.applicationinsights.agent.bootstrap.configuration.Configuration.SelfDiagnostics;
import com.microsoft.applicationinsights.agent.bootstrap.diagnostics.DiagnosticsHelper;
import com.microsoft.applicationinsights.agent.bootstrap.diagnostics.status.StatusFile;
import io.opentelemetry.javaagent.bootstrap.ConfigureLogging;
import io.opentelemetry.javaagent.bootstrap.AgentInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

// this class has one purpose, start diagnostics before passing control to opentelemetry-auto-instr-java
public class MainEntryPoint {

    private static Configuration configuration;
    private static Path configPath;
    private static long lastModifiedTime;

    private MainEntryPoint() {
    }

    public static Configuration getConfiguration() {
        return configuration;
    }

    public static Path getConfigPath() {
        return configPath;
    }

    public static long getLastModifiedTime() {
        return lastModifiedTime;
    }

    public static void start(Instrumentation instrumentation, URL bootstrapURL) {
        boolean success = false;
        Logger startupLogger = null;
        try {
            File agentJarFile = new File(bootstrapURL.toURI());
            DiagnosticsHelper.setAgentJarFile(agentJarFile);
            // configuration is only read this early in order to extract logging configuration
            configuration = ConfigurationBuilder.create(agentJarFile.toPath());
            configPath = configuration.configPath;
            lastModifiedTime = configuration.lastModifiedTime;
            startupLogger = configureLogging(configuration.preview.selfDiagnostics);
            ConfigurationBuilder.logConfigurationMessages();
            MDC.put(DiagnosticsHelper.MDC_PROP_OPERATION, "Startup");
            AgentInitializer.start(instrumentation, bootstrapURL, false);
            success = true;
            LoggerFactory.getLogger(DiagnosticsHelper.DIAGNOSTICS_LOGGER_NAME)
                    .info("Application Insights Codeless Agent Attach Successful");
        } catch (ThreadDeath td) {
            throw td;
        } catch (Throwable t) {
            if (startupLogger != null) {
                startupLogger.error("Agent failed to start.", t);
            } else {
                t.printStackTrace();
            }
        } finally {
            try {
                StatusFile.putValueAndWrite("AgentInitializedSuccessfully", success);
            } catch (Exception e) {
                startupLogger.error("Error writing status.json", e); // lgtm[java/dereferenced-value-may-be-null]
            }
            MDC.clear();
        }
    }

    private static Logger configureLogging(SelfDiagnostics selfDiagnostics) {
        String logbackXml;
        String destination = selfDiagnostics.destination;
        boolean logUnknownDestination = false;
        if (DiagnosticsHelper.isAppServiceCodeless()) {
            // User-accessible IPA log file. Enabled by default.
            if ("false".equalsIgnoreCase(System.getenv(DiagnosticsHelper.IPA_LOG_FILE_ENABLED_ENV_VAR))) {
                System.setProperty("ai.config.appender.user-logdir.location", "");
            }

            // Diagnostics IPA log file location. Disabled by default.
            final String internalLogOutputLocation = System.getenv(DiagnosticsHelper.INTERNAL_LOG_OUTPUT_DIR_ENV_VAR);
            if (internalLogOutputLocation == null || internalLogOutputLocation.isEmpty()) {
                System.setProperty("ai.config.appender.diagnostics.location", "");
            }

            // Diagnostics IPA ETW provider. Windows-only. Enabled by default.
            if (!DiagnosticsHelper.isOsWindows() || "false".equalsIgnoreCase(System.getenv(DiagnosticsHelper.IPA_ETW_PROVIDER_ENABLED_ENV_VAR))) {
                System.setProperty("ai.config.appender.etw.location", "");
            }
            logbackXml = "applicationinsights.appsvc.logback.xml";
        } else if (destination == null || destination.equalsIgnoreCase("console")) {
            logbackXml = "applicationinsights.console.logback.xml";
        } else if (destination.equalsIgnoreCase("file")) {
            logbackXml = "applicationinsights.file.logback.xml";
        } else {
            logUnknownDestination = true;
            logbackXml = "applicationinsights.console.logback.xml";
        }
        ClassLoader cl = ConfigureLogging.class.getClassLoader();
        if (cl == null) {
            cl = ClassLoader.getSystemClassLoader();
        }
        final URL configurationFile = cl.getResource(logbackXml);
        System.setProperty("applicationinsights.logback.configurationFile", configurationFile.toString());

        String logbackDirectory = selfDiagnostics.directory;
        String logbackLevel = selfDiagnostics.level;
        int logbackMaxFileSizeMB = selfDiagnostics.maxSizeMB;

        if (logbackDirectory == null) {
            logbackDirectory = System.getProperty("java.io.tmpdir");
        }
        if (logbackDirectory == null) {
            // this should never get to here, but just in case, otherwise setProperty() will fail below
            logbackDirectory = ".";
        }
        System.setProperty("applicationinsights.logback.directory", logbackDirectory);
        System.setProperty("applicationinsights.logback.level", logbackLevel);
        System.setProperty("applicationinsights.logback.maxFileSize", logbackMaxFileSizeMB + "MB");
        System.setProperty("applicationinsights.logback.totalSizeCap", logbackMaxFileSizeMB * 2 + "MB");
        if (isDebugOrLower(logbackLevel)) {
            // never want to log apache http at trace or debug, it's just way to verbose
            System.setProperty("applicationinsights.logback.level.org.apache.http", "info");
        } else {
            System.setProperty("applicationinsights.logback.level.org.apache.http", logbackLevel);
        }
        try {
            Logger logger = LoggerFactory.getLogger("com.microsoft.applicationinsights.agent");
            if (logUnknownDestination) {
                logger.error("Unknown self-diagnostics destination: {}", destination);
            }
            return logger;
        } finally {
            System.clearProperty("applicationinsights.logback.configurationFile");
            System.clearProperty("applicationinsights.logback.directory");
            System.clearProperty("applicationinsights.logback.level");
            System.clearProperty("applicationinsights.logback.maxFileSize");
            System.clearProperty("applicationinsights.logback.totalSizeCap");
            System.clearProperty("applicationinsights.logback.level.org.apache.http");
        }
    }

    private static boolean isDebugOrLower(String level) {
        return level.equalsIgnoreCase("all") || level.equalsIgnoreCase("trace") || level.equalsIgnoreCase("debug");
    }
}
