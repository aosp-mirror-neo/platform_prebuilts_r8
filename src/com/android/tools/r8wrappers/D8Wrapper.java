/*
 * Copyright (C) 2023 The Android Open Source Project
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

import com.android.tools.r8.ArchiveProgramResourceProvider;
import com.android.tools.r8.CompilationFailedException;
import com.android.tools.r8.D8;
import com.android.tools.r8.D8Command;
import com.android.tools.r8.ParseFlagInfo;
import com.android.tools.r8.ParseFlagPrinter;
import com.android.tools.r8.Version;
import com.android.tools.r8.origin.Origin;
import com.android.tools.r8wrappers.utils.WrapperDiagnosticsHandler;
import com.android.tools.r8wrappers.utils.WrapperFlag;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutionException;

public class D8Wrapper {

  private static final String WRAPPER_STRING = "d8-aosp-wrapper";

  protected static final Origin CLI_ORIGIN =
      new Origin(Origin.root()) {
        @Override
        public String part() {
          return WRAPPER_STRING;
        }
      };

  protected static final String NO_DEX_FLAG = "--no-dex-input-jar";
  protected static final String INFO_FLAG = "--info";
  protected static final String PACKAGE_RSP = "--packages";
  protected static final String MODIFIED_PACKAGE_RSP = "--mod-packages";
  protected static final String PACKAGE_OUTPUT = "--package-output";
  protected static final String VERBOSE_SYNTHETIC_NAMES_FLAG = "--verbose-synthetic-names";

  private static List<ParseFlagInfo> getAdditionalFlagsInfo() {
    return Arrays.asList(
        new WrapperFlag(NO_DEX_FLAG, "Input archive with potential all dex code ignored."),
        new WrapperFlag(INFO_FLAG, "Print the info-level log messages from the compiler."),
        new WrapperFlag(PACKAGE_RSP,
                "List of packages present across all .class files in a jar.\n" +
                        "When present, package based dex is used.\n" +
                        "Should be passed as a rsp/text file containing packages separated by" +
                        "whitespace."),
        new WrapperFlag(MODIFIED_PACKAGE_RSP,
                "List of modified present across all .class files in a jar, between this " +
                        "and previous iteration, for incremental dex.\n" +
                        "Should be passed as a rsp/text file containing packages separated by " +
                        "whitespace."),
        new WrapperFlag(VERBOSE_SYNTHETIC_NAMES_FLAG,
                "Enable verbose synthetic names that use the `$$ExternalSynthetic` marker."));
  }

  private static String getUsageMessage() {
    StringBuilder builder =
        appendLines(
            new StringBuilder(),
            "Usage: d8 [options] [@<argfile>] <input-files>",
            " where <input-files> are any combination of dex, class, zip, jar or apk files",
            " and each <argfile> is a file containing additional arguments (one per line)",
            " and options are:");
    new ParseFlagPrinter()
        .addFlags(D8Command.getParseFlagsInformation())
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

  private static boolean isPackageBasedCompilation(String[] args) {
    for (int i = 0; i < args.length; i++) {
      if (args[i].equals(PACKAGE_RSP)) {
        return true;
      }
    }
    return false;
  }

  public static void main(String[] args)
      throws CompilationFailedException, IOException, ExecutionException, InterruptedException {
    boolean packageBasedCompilation = isPackageBasedCompilation(args);
    D8Wrapper wrapper = packageBasedCompilation ? new D8PackageBasedWrapper() : new D8Wrapper();
    String[] remainingArgs = wrapper.parseWrapperArguments(args);
    if (printHelpOrVersion(wrapper, remainingArgs)) {
      return;
    }
    wrapper.run(remainingArgs);
  }

  public void run(String[] remainingArgs)
      throws CompilationFailedException, IOException, ExecutionException, InterruptedException {
    D8Command.Builder builder = D8Command.parse(
        remainingArgs, CLI_ORIGIN, diagnosticsHandler);
    applyWrapperArguments(builder);
    R8Wrapper.applyCommonCompilerArguments(builder);
    D8.run(builder.build());
  }

  private static boolean printHelpOrVersion(D8Wrapper wrapper, String[] remainingArgs) {
    D8Command.Builder builder = D8Command.parse(
        remainingArgs, CLI_ORIGIN, wrapper.diagnosticsHandler);
    if (builder.isPrintHelp()) {
      System.out.println(getUsageMessage());
      return true;
    }
    if (builder.isPrintVersion()) {
      System.out.println("D8(" + WRAPPER_STRING + ") " + Version.getVersionString());
      return true;
    }
    return false;
  }

  protected WrapperDiagnosticsHandler diagnosticsHandler = new WrapperDiagnosticsHandler();
  protected boolean printInfoDiagnostics = false;
  protected boolean verboseSyntheticNames = false;
  protected List<Path> noDexArchives = new ArrayList<>();

  public String[] parseWrapperArguments(String[] args) {
    List<String> remainingArgs = new ArrayList<>();
    for (int i = 0; i < args.length; i++) {
      String arg = args[i];
      switch (arg) {
        case INFO_FLAG: {
          printInfoDiagnostics = true;
          break;
        }
        case NO_DEX_FLAG: {
          if (++i >= args.length) {
            throw new RuntimeException("Missing argument to " + NO_DEX_FLAG);
          }
          Path path = Paths.get(args[i]);
          if (!Files.isRegularFile(path)) {
            throw new RuntimeException("Unexpected argument to " + NO_DEX_FLAG +
                ". Expected an archive");
          }
          noDexArchives.add(path);
          break;
        }
        case VERBOSE_SYNTHETIC_NAMES_FLAG: {
          verboseSyntheticNames = true;
          break;
        }
        default: {
          remainingArgs.add(arg);
          break;
        }
      }
    }
    return remainingArgs.toArray(new String[0]);
  }

  private void applyWrapperArguments(D8Command.Builder builder) throws IOException {
    diagnosticsHandler.setWarnOnUnsupportedMainDexList(true);
    diagnosticsHandler.setPrintInfoDiagnostics(printInfoDiagnostics);
    for (Path path : noDexArchives) {
      builder.addProgramResourceProvider(
          ArchiveProgramResourceProvider.fromArchive(
              path,
              ArchiveProgramResourceProvider::includeClassFileEntries));
    }
    builder.setEnableVerboseSyntheticNames(verboseSyntheticNames);
  }
}
