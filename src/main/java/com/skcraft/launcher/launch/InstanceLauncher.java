/*
 * SK's Minecraft Launcher
 * Copyright (C) 2010-2014 Albert Pham <http://www.sk89q.com> and contributors
 * Please see LICENSE.txt for license information.
 */

package com.skcraft.launcher.launch;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Strings;
import com.google.common.io.Files;
import com.skcraft.concurrency.DefaultProgress;
import com.skcraft.concurrency.ProgressObservable;
import com.skcraft.launcher.AssetsRoot;
import com.skcraft.launcher.Configuration;
import com.skcraft.launcher.Instance;
import com.skcraft.launcher.Launcher;
import com.skcraft.launcher.auth.Session;
import com.skcraft.launcher.install.ZipExtract;
import com.skcraft.launcher.model.minecraft.AssetsIndex;
import com.skcraft.launcher.model.minecraft.Library;
import com.skcraft.launcher.model.minecraft.VersionManifest;
import com.skcraft.launcher.util.Environment;
import com.skcraft.launcher.util.Platform;
import lombok.Getter;
import lombok.NonNull;
import lombok.Setter;
import lombok.extern.java.Log;
import org.apache.commons.lang.text.StrSubstitutor;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

import static com.skcraft.launcher.LauncherUtils.checkInterrupted;
import static com.skcraft.launcher.util.SharedLocale._;

/**
 * Handles the launching of an instance.
 */
@Log
public class InstanceLauncher implements Callable<Process>, ProgressObservable {

    private ProgressObservable progress = new DefaultProgress(0, _("instanceLauncher.preparing"));

    private final ObjectMapper mapper = new ObjectMapper();
    private final Launcher launcher;
    private final Instance instance;
    private final Session session;
    private final File extractDir;
    @Getter @Setter private Environment environment = Environment.getInstance();

    private VersionManifest versionManifest;
    private AssetsIndex assetsIndex;
    private File virtualAssetsDir;
    private Configuration config;
    private JavaProcessBuilder builder;
    private AssetsRoot assetsRoot;

    /**
     * Create a new instance launcher.
     *
     * @param launcher the launcher
     * @param instance the instance
     * @param session the session
     * @param extractDir the directory to extract to
     */
    public InstanceLauncher(@NonNull Launcher launcher, @NonNull Instance instance,
                            @NonNull Session session, @NonNull File extractDir) {
        this.launcher = launcher;
        this.instance = instance;
        this.session = session;
        this.extractDir = extractDir;
    }

    /**
     * Get the path to the JAR.
     *
     * @return the JAR path
     */
    private File getJarPath() {
        File jarPath = instance.getCustomJarPath();
        if (!jarPath.exists()) {
            jarPath = launcher.getJarPath(versionManifest);
        }
        return jarPath;
    }

    @Override
    public Process call() throws Exception {
        config = launcher.getConfig();
        builder = new JavaProcessBuilder();
        assetsRoot = launcher.getAssets();

        // Load versionManifest and assets index
        versionManifest = mapper.readValue(instance.getVersionPath(), VersionManifest.class);
        assetsIndex = mapper.readValue(assetsRoot.getIndexPath(versionManifest), AssetsIndex.class);

        // Copy over assets to the tree
        progress = new DefaultProgress(0.1, _("instanceLauncher.preparingAssets"));
        virtualAssetsDir = assetsRoot.buildAssetTree(versionManifest);

        progress = new DefaultProgress(0.9, _("instanceLauncher.collectingArgs"));

        addJvmArgs();
        addLibraries();
        addJarArgs();
        addProxyArgs();
        addWindowArgs();
        addPlatformArgs();

        builder.classPath(getJarPath());
        builder.setMainClass(versionManifest.getMainClass());

        ProcessBuilder processBuilder = new ProcessBuilder(builder.buildCommand());
        processBuilder.directory(instance.getContentDir());
        log.info("Launching: " + builder);
        checkInterrupted();

        progress = new DefaultProgress(1, _("instanceLauncher.startingJava"));

        return processBuilder.start();
    }

    /**
     * Add platform-specific arguments.
     */
    private void addPlatformArgs() {
        // Mac OS X arguments
        if (getEnvironment().getPlatform() == Platform.MAC_OS_X) {
            File icnsPath = assetsIndex.getObjectPath(assetsRoot, "icons/minecraft.icns");
            if (icnsPath != null) {
                builder.getFlags().add("-Xdock:icon=" + icnsPath.getAbsolutePath());
                builder.getFlags().add("-Xdock:name=Minecraft");
            }
        }

        // Windows arguments
        if (getEnvironment().getPlatform() == Platform.WINDOWS) {
            builder.getFlags().add("-XX:HeapDumpPath=MojangTricksIntelDriversForPerformance_javaw.exe_minecraft.exe.heapdump");
        }
    }

    /**
     * Add libraries.
     */
    private void addLibraries() {
        // Add libraries to classpath or extract the libraries as necessary
        for (Library library : versionManifest.getLibraries()) {
            File path = new File(launcher.getLibrariesDir(), library.getPath(environment));

            if (path.exists()) {
                Library.Extract extract = library.getExtract();
                if (extract != null) {
                    ZipExtract zipExtract = new ZipExtract(Files.asByteSource(path), extractDir);
                    zipExtract.setExclude(extract.getExclude());
                    zipExtract.run();
                } else {
                    builder.classPath(path);
                }
            }
        }

        builder.getFlags().add("-Djava.library.path=" + extractDir.getAbsoluteFile());
    }

    /**
     * Add JVM arguments.
     *
     * @throws IOException on I/O error
     */
    private void addJvmArgs() throws IOException {
        int minMemory = config.getMinMemory();
        int maxMemory = config.getMaxMemory();
        int permGen = config.getPermGen();

        if (minMemory <= 0) {
            minMemory = 1024;
        }

        if (maxMemory <= 0) {
            maxMemory = 1024;
        }

        if (permGen <= 0) {
            permGen = 128;
        }

        if (permGen <= 64) {
            permGen = 64;
        }

        if (minMemory > maxMemory) {
            maxMemory = minMemory;
        }

        builder.setMinMemory(minMemory);
        builder.setMaxMemory(maxMemory);
        builder.setPermGen(permGen);

        String rawJvmPath = config.getJvmPath();
        if (!Strings.isNullOrEmpty(rawJvmPath)) {
            builder.tryJvmPath(new File(rawJvmPath));
        }

        String rawJvmArgs = config.getJvmArgs();
        if (!Strings.isNullOrEmpty(rawJvmArgs)) {
            List<String> flags = builder.getFlags();

            for (String arg : JavaProcessBuilder.splitArgs(rawJvmArgs)) {
                flags.add(arg);
            }
        }
    }

    /**
     * Add arguments for the application.
     *
     * @throws JsonProcessingException on error
     */
    private void addJarArgs() throws JsonProcessingException {
        List<String> args = builder.getArgs();

        String[] rawArgs = versionManifest.getMinecraftArguments().split(" +");
        StrSubstitutor substitutor = new StrSubstitutor(getCommandSubstitutions());
        for (String arg : rawArgs) {
            args.add(substitutor.replace(arg));
        }
    }

    /**
     * Add proxy arguments.
     */
    private void addProxyArgs() {
        List<String> args = builder.getArgs();

        if (config.isProxyEnabled()) {
            String host = config.getProxyHost();
            int port = config.getProxyPort();
            String username = config.getProxyUsername();
            String password = config.getProxyPassword();

            if (!Strings.isNullOrEmpty(host) && port > 0 && port < 65535) {
                args.add("--proxyHost");
                args.add(config.getProxyHost());
                args.add("--proxyPort");
                args.add(String.valueOf(port));

                if (!Strings.isNullOrEmpty(username)) {
                    builder.getArgs().add("--proxyUser");
                    builder.getArgs().add(username);
                    builder.getArgs().add("--proxyPass");
                    builder.getArgs().add(password);
                }
            }
        }
    }

    /**
     * Add window arguments.
     */
    private void addWindowArgs() {
        List<String> args = builder.getArgs();
        int width = config.getWindowWidth();
        int height = config.getWidowHeight();

        if (width >= 10) {
            args.add("--width");
            args.add(String.valueOf(width));
            args.add("--height");
            args.add(String.valueOf(height));
        }
    }

    /**
     * Build the list of command substitutions.
     *
     * @return the map of substitutions
     * @throws JsonProcessingException on error
     */
    private Map<String, String> getCommandSubstitutions() throws JsonProcessingException {
        Map<String, String> map = new HashMap<String, String>();

        map.put("version_name", versionManifest.getId());

        map.put("auth_access_token", session.getAccessToken());
        map.put("auth_session", session.getSessionToken());
        map.put("auth_player_name", session.getName());
        map.put("auth_uuid", session.getUuid());

        map.put("profile_name", session.getName());
        map.put("user_type", session.getUserType().getName());
        map.put("user_properties", mapper.writeValueAsString(session.getUserProperties()));

        map.put("game_directory", instance.getContentDir().getAbsolutePath());
        map.put("game_assets", virtualAssetsDir.getAbsolutePath());
        map.put("assets_root", launcher.getAssets().getDir().getAbsolutePath());
        map.put("assets_index_name", versionManifest.getAssetsIndex());

        return map;
    }

    @Override
    public double getProgress() {
        return -1;
    }

    @Override
    public String getStatus() {
        return null;
    }

}