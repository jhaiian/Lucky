package com.jhaiian.lucky.gradle;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javax.inject.Inject;

import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.TaskAction;
import org.gradle.process.ExecOperations;

public abstract class CargoNdkBuild extends DefaultTask {

    @Input
    public abstract ListProperty<String> getAbis();

    @Input
    public abstract Property<Integer> getApiLevel();

    @Internal
    public abstract DirectoryProperty getEngineDir();

    @Input
    @Optional
    public abstract Property<String> getNdkPath();

    @Internal
    public abstract DirectoryProperty getNdkDir();

    @OutputDirectory
    public abstract DirectoryProperty getOutputDir();

    @Inject
    protected abstract ExecOperations getExecOperations();

    public CargoNdkBuild() {
        getOutputs().upToDateWhen(task -> false);
    }

    private static String findCargo() {
        List<String> candidates = new ArrayList<>();
        String fromEnv = System.getenv("CARGO");
        if (fromEnv != null) {
            candidates.add(fromEnv);
        }
        String home = System.getProperty("user.home");
        candidates.add(home + "/.cargo/bin/cargo");
        candidates.add(home + "/.cargo/bin/cargo.exe");
        for (String candidate : candidates) {
            File file = new File(candidate);
            if (file.canExecute()) {
                return file.getAbsolutePath();
            }
        }
        return "cargo";
    }

    private static void deleteRecursively(File file) {
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteRecursively(child);
            }
        }
        file.delete();
    }

    @TaskAction
    public void build() {
        File engine = getEngineDir().get().getAsFile();
        File ndk;
        if (getNdkPath().isPresent() && !getNdkPath().get().isEmpty()) {
            ndk = new File(getNdkPath().get());
        } else if (getNdkDir().isPresent()) {
            ndk = getNdkDir().get().getAsFile();
        } else {
            throw new GradleException("Android NDK not found. Set ANDROID_NDK_HOME or install the NDK from the SDK manager.");
        }
        File output = getOutputDir().get().getAsFile();
        List<String> targets = getAbis().get();
        String api = String.valueOf(getApiLevel().get());
        boolean windows = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("windows");

        if (windows) {
            List<String> missing = new ArrayList<>();
            for (String abi : targets) {
                if (!new File(engine, "native/out/" + abi + "/deps.id").exists()) {
                    missing.add(abi);
                }
            }
            if (!missing.isEmpty()) {
                throw new GradleException("Native dependencies for " + String.join(", ", missing)
                        + " are missing. Run engine/native/build-deps.sh on Linux or macOS.");
            }
        } else {
            List<String> command = new ArrayList<>();
            command.add("bash");
            command.add(new File(engine, "native/build-deps.sh").getAbsolutePath());
            command.addAll(targets);
            getExecOperations().exec(spec -> {
                spec.setWorkingDir(engine);
                spec.commandLine(command);
                spec.environment("ANDROID_NDK_HOME", ndk.getAbsolutePath());
                spec.environment("ANDROID_NDK_ROOT", ndk.getAbsolutePath());
            });
        }

        deleteRecursively(output);
        output.mkdirs();

        List<String> arguments = new ArrayList<>();
        arguments.add("ndk");
        for (String abi : targets) {
            arguments.add("-t");
            arguments.add(abi);
        }
        arguments.add("-P");
        arguments.add(api);
        arguments.add("-o");
        arguments.add(output.getAbsolutePath());
        arguments.add("build");
        arguments.add("--release");
        arguments.add("-p");
        arguments.add("lucky-jni");
        String cargo = findCargo();
        getExecOperations().exec(spec -> {
            spec.setWorkingDir(engine);
            spec.setExecutable(cargo);
            spec.args(arguments);
            spec.environment("ANDROID_NDK_HOME", ndk.getAbsolutePath());
            spec.environment("ANDROID_NDK_ROOT", ndk.getAbsolutePath());
            spec.environment("LUCKY_ANDROID_API", api);
        });
    }
}
