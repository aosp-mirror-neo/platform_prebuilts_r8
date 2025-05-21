/*
 * Copyright (C) 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.tools.r8wrappers;

import com.android.tools.r8.AndroidResourceInput;
import com.android.tools.r8.ArchiveProtoAndroidResourceConsumer;
import com.android.tools.r8.ArchiveProtoAndroidResourceProvider;
import com.android.tools.r8.BaseCompilerCommand;
import com.android.tools.r8.CompilationFailedException;
import com.android.tools.r8.DiagnosticsLevel;
import com.android.tools.r8.ParseFlagInfo;
import com.android.tools.r8.ParseFlagPrinter;
import com.android.tools.r8.R8;
import com.android.tools.r8.R8Command;
import com.android.tools.r8.ResourceException;
import com.android.tools.r8.ResourcePath;
import com.android.tools.r8.Version;
import com.android.tools.r8.origin.Origin;
import com.android.tools.r8.origin.PathOrigin;
import com.android.tools.r8wrappers.utils.DepsFileWriter;
import com.android.tools.r8wrappers.utils.WrapperDiagnosticsHandler;
import com.android.tools.r8wrappers.utils.WrapperFlag;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

public class R8Wrapper {

  private static final String WRAPPER_STRING = "r8-aosp-wrapper";

  private static final Origin CLI_ORIGIN =
      new Origin(Origin.root()) {
        @Override
        public String part() {
          return WRAPPER_STRING;
        }
      };

  private static List<ParseFlagInfo> getAdditionalFlagsInfo() {
    return Arrays.asList(
        new WrapperFlag("--deps-file <file>", "Write input dependencies to <file>."),
        new WrapperFlag("--info", "Print the info-level log messages from the compiler."),
        new WrapperFlag("--resource-input", "Resource input for the resource shrinker."),
        new WrapperFlag("--resource-output", "Resource shrinker output."),
        new WrapperFlag("--optimized-resource-shrinking", "Use R8 optimizing resource pipeline."),
        new WrapperFlag("--protect-api-surface", "API surface protection for libcore."),
        new WrapperFlag(
            "--store-store-fence-constructor-inlining",
            "Use aggressive R8 constructor inlining."),
        new WrapperFlag(
            "--no-implicit-default-init",
            "Disable compat-mode behavior of keeping default constructors in full mode."),
        new WrapperFlag(
            "--exclude <file>",
            "Path to file containing name of classes that should not be compiled using R8."));
  }

  private static String getUsageMessage() {
    StringBuilder builder =
        appendLines(
            new StringBuilder(),
            "Usage: r8 [options] [@<argfile>] <input-files>",
            " where <input-files> are any combination of class, zip, or jar files",
            " and each <argfile> is a file containing additional arguments (one per line)",
            " and options are:");
    new ParseFlagPrinter()
        .addFlags(R8Command.getParseFlagsInformation())
        .addFlags(getAdditionalFlagsInfo())
        .setIndent(2)
        .appendLinesToBuilder(builder);
    return builder.toString();
  }

  private static StringBuilder appendLines(StringBuilder builder, String... lines) {
    for (String line : lines) {
      builder.append(line).append(System.lineSeparator());
    }
    return builder;
  }

  public static void main(String[] args) throws CompilationFailedException, IOException {
    // Disable this optimization as it can impact weak reference semantics. See b/233432839.
    System.setProperty("com.android.tools.r8.disableEnqueuerDeferredTracing", "1");
    // Disable class merging across different files to improve attribution. See b/242881914.
    System.setProperty("com.android.tools.r8.enableSameFilePolicy", "1");
    // Enable experimental -whyareyounotinlining config to aid debugging. See b/277389461.
    System.setProperty("com.android.tools.r8.experimental.enablewhyareyounotinlining", "1");
    // Allow use of -convertchecknotnull optimization. See b/280633711.
    System.setProperty("com.android.tools.r8.experimental.enableconvertchecknotnull", "1");
    // Allow conditional keep rule application against library references. See b/386409781.
    System.setProperty("com.android.tools.r8.applyIfRulesToLibrary", "1");
    // Do not keep runtime invisible annotations with @KeepForApi. See b/399021897.
    System.setProperty("com.android.tools.r8.keepanno.unkeepInvisibleAnnotationsInKeepForApi", "1");

    R8Wrapper wrapper = new R8Wrapper();
    String[] remainingArgs = wrapper.parseWrapperArguments(args);
    if (!wrapper.useCompatPg && !wrapper.noImplicitDefaultInit) {
      // Retain incorrect behavior in full mode that will implicitly keep default constructors.
      // See b/132318799.
      System.setProperty(
          "com.android.tools.r8.enableEmptyMemberRulesToDefaultInitRuleConversion",
          "1");
    }
    R8Command.Builder builder = R8Command.parse(
        remainingArgs, CLI_ORIGIN, wrapper.diagnosticsHandler);
    if (builder.isPrintHelp()) {
      System.out.println(getUsageMessage());
      return;
    }
    if (builder.isPrintVersion()) {
      System.out.println("R8(" + WRAPPER_STRING + ") " + Version.getVersionString());
      return;
    }
    wrapper.applyWrapperArguments(builder);
    applyCommonCompilerArguments(builder);
    builder.setEnableExperimentalKeepAnnotations(true);
    R8.run(builder.build());
  }

  private WrapperDiagnosticsHandler diagnosticsHandler = new WrapperDiagnosticsHandler();
  private boolean ignoreLibraryExtendsProgram = false;
  private boolean useCompatPg = false;
  private Path depsOutput = null;
  private Path resourceInput = null;
  private Path resourceOutput = null;
  private final List<String> pgRules = new ArrayList<>();
  private boolean printInfoDiagnostics = false;
  private boolean dontOptimize = false;
  private boolean keepRuntimeInvisibleAnnotations = false;
  private boolean optimizingResourceShrinking = false;
  private boolean forceOptimizingResourceShrinking = false;
  private boolean noImplicitDefaultInit = false;
  private boolean protectApiSurface = false;
  private boolean storeStoreFenceConstructorInlining = false;
  private final List<String> excludeClasses = new ArrayList<>();

  private String[] parseWrapperArguments(String[] args) throws IOException {
    List<String> remainingArgs = new ArrayList<>();
    for (int i = 0; i < args.length; i++) {
      String arg = args[i];
      switch (arg) {
        case "--exclude":
          {
            String nextArg = args[++i];
            Path excludeFile = Paths.get(nextArg);
            for (String line : Files.readAllLines(excludeFile)) {
              line = line.trim();
              if (line.isEmpty() || line.startsWith("#")) {
                continue;
              }
              excludeClasses.add(line);
            }
            break;
          }
        case "--ignore-library-extends-program":
          {
            ignoreLibraryExtendsProgram = true;
            break;
          }
        case "--info":
          {
            printInfoDiagnostics = true;
            break;
          }
        case "--keep-runtime-invisible-annotations":
          {
            keepRuntimeInvisibleAnnotations = true;
            break;
          }
        case "--resource-input":
          {
            if (resourceInput != null) {
              throw new RuntimeException("Only one --resource-input flag accepted");
            }
            String nextArg = args[++i];
            resourceInput = Paths.get(nextArg);
            break;
          }
        case "--resource-output":
          {
            if (resourceOutput != null) {
              throw new RuntimeException("Only one --resource-output flag accepted");
            }
            String nextArg = args[++i];
            resourceOutput = Paths.get(nextArg);
            break;
          }
        case "--optimized-resource-shrinking":
          {
            optimizingResourceShrinking = true;
            break;
          }
        case "--force-optimized-resource-shrinking":
          {
            forceOptimizingResourceShrinking = true;
            break;
          }
        case "--no-implicit-default-init":
          {
            noImplicitDefaultInit = true;
            break;
          }
        case "--deps-file":
          {
            String nextArg = args[++i];
            depsOutput = Paths.get(nextArg);
            break;
          }
          // Remove uses of this same as for D8 (b/69377755).
        case "--multi-dex":
          {
            break;
          }
          // TODO(zerny): replace uses with --pg-compat
        case "--force-proguard-compatibility":
          {
            useCompatPg = true;
            break;
          }
          // Zero argument PG rules.
        case "-dontshrink":
        case "-dontobfuscate":
        case "-ignorewarnings":
          {
            pgRules.add(arg);
            break;
          }
        case "-dontoptimize":
          {
            dontOptimize = true;
            pgRules.add(arg);
            break;
          }
          // One argument PG rules.
        case "-injars":
        case "-libraryjars":
        case "-include":
        case "-printmapping":
        case "-printconfiguration":
        case "-printusage":
        case "-printseeds":
          {
            pgRules.add(arg + " " + args[++i]);
            break;
          }
        case "--protect-api-surface":
          {
            protectApiSurface = true;
            break;
          }
        case "--store-store-fence-constructor-inlining":
          {
            storeStoreFenceConstructorInlining = true;
            break;
          }
        default:
          {
            remainingArgs.add(arg);
            break;
          }
      }
    }
    return remainingArgs.toArray(new String[0]);
  }

  private void applyWrapperArguments(R8Command.Builder builder) {
    diagnosticsHandler.setPrintInfoDiagnostics(printInfoDiagnostics);
    // Surface duplicate type warnings for optimized targets where duplicates are more dangerous.
    // TODO(b/222468116): Bump the level to ERROR for all optimized targets after resolving current
    // duplicates, and the default level to WARNING.
    if (!dontOptimize) {
      diagnosticsHandler.setDuplicateTypesDiagnosticsLevel(DiagnosticsLevel.WARNING);
    }
    if (depsOutput != null) {
      Path codeOutput = builder.getOutputPath();
      Path target = Files.isDirectory(codeOutput) ? codeOutput.resolve("classes.dex") : codeOutput;
      builder.setInputDependencyGraphConsumer(new DepsFileWriter(target, depsOutput.toString()));
    }
    if (resourceInput != null && resourceOutput != null) {
      builder.setAndroidResourceProvider(new AOSPResourceProvider(resourceInput,
          new PathOrigin(resourceInput)));
      builder.setAndroidResourceConsumer(
          new ArchiveProtoAndroidResourceConsumer(resourceOutput, resourceInput));
      if (optimizingResourceShrinking) {
        builder.setResourceShrinkerConfiguration(b -> b.enableOptimizedShrinkingWithR8().build());
        if (!forceOptimizingResourceShrinking) {
          // TODO(b/372264901): There is a range of test targets that rely on using ids for looking
          // up ui elements. For now, keep all of these.
          builder.addProguardConfiguration(List.of("-keep class **.R$id {<fields>;}"),
              CLI_ORIGIN);
        }
      }
    } else if (resourceOutput != null || resourceInput != null) {
      throw new RuntimeException("Both --resource-input and --resource-output must be specified");
    }
    if (ignoreLibraryExtendsProgram) {
      System.setProperty("com.android.tools.r8.allowLibraryExtendsProgramInFullMode", "1");
    }
    if (keepRuntimeInvisibleAnnotations) {
      builder.addProguardConfiguration(
          List.of(
              "-keepattributes RuntimeInvisibleAnnotations",
              "-keepattributes RuntimeInvisibleParameterAnnotations",
              "-keepattributes RuntimeInvisibleTypeAnnotations"),
          CLI_ORIGIN);
    }
    if (!excludeClasses.isEmpty()) {
      String includePatterns = "**";
      String excludePatterns = String.join(",", excludeClasses);
      builder.enableExperimentalPartialShrinking(includePatterns, excludePatterns);
    }
    if (!pgRules.isEmpty()) {
      builder.addProguardConfiguration(pgRules, CLI_ORIGIN);
    }
    if (protectApiSurface) {
      builder.setProtectApiSurface(true);
    }
    if (useCompatPg) {
      builder.setProguardCompatibility(useCompatPg);
    }
    if (storeStoreFenceConstructorInlining) {
      System.setProperty("com.android.tools.r8.enableConstructorInliningWithFinalFields", "1");
    }
  }

  /** Utility method to apply platform specific settings to both D8 and R8. */
  public static void applyCommonCompilerArguments(BaseCompilerCommand.Builder<?, ?> builder) {
    // TODO(b/232073181): Remove this once platform flag is the default.
    if (!builder.getAndroidPlatformBuild()) {
      System.setProperty("com.android.tools.r8.disableApiModeling", "1");
    }
  }

  private static class AOSPResourceProvider extends ArchiveProtoAndroidResourceProvider {
    final String defaultXmlRules = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
        + "<resources xmlns:tools=\"http://schemas.android.com/tools\"\n"
        + "    tools:shrinkMode=\"strict\"\n"
        + "    tools:keep=\"@id/*\"\n"
        + "/>\n";

    final AndroidResourceInput defaultRules = new AndroidResourceInput() {
      @Override
      public ResourcePath getPath() {
        return new ResourcePath() {
          @Override
          public String location() {
            return "res/raw/asop_default.xml";
          }
        };
      }

      @Override
      public Kind getKind() {
        return Kind.KEEP_RULE_FILE;
      }

      @Override
      public InputStream getByteStream() throws ResourceException {
        return new ByteArrayInputStream(defaultXmlRules.getBytes(StandardCharsets.UTF_8));
      }

      @Override
      public Origin getOrigin() {
        return new PathOrigin(Paths.get("R8Wrapper.java"));
      }
    };

    public AOSPResourceProvider(Path archive, Origin origin) {
      super(archive, origin);
    }

    @Override
    public Collection<AndroidResourceInput> getAndroidResources() throws ResourceException {
      ArrayList<AndroidResourceInput> androidResourceInputs = new ArrayList<>(
          super.getAndroidResources());
      androidResourceInputs.add(defaultRules);
      return androidResourceInputs;
    }
  }
}
