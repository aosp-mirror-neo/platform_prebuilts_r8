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

import com.android.tools.r8.ArchiveClassFileProvider;
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

public class D8Wrapper {

  private static final String WRAPPER_STRING = "d8-aosp-wrapper";

  private static final Origin CLI_ORIGIN =
      new Origin(Origin.root()) {
        @Override
        public String part() {
          return WRAPPER_STRING;
        }
      };

  private static final String NO_DEX_FLAG = "--no-dex-input-jar";
  private static final String INFO_FLAG = "--info";
  private static final String SHARD_COUNT_FLAG = "--shard-count";
  private static final String SHARD_NUMBER_FLAG = "--shard-number";

  private static List<ParseFlagInfo> getAdditionalFlagsInfo() {
    return Arrays.asList(
        new WrapperFlag(NO_DEX_FLAG, "Input archive with potential all dex code ignored."),
        new WrapperFlag(SHARD_COUNT_FLAG, "Number of shards in total."),
        new WrapperFlag(SHARD_NUMBER_FLAG, "Current shard."),
        new WrapperFlag(INFO_FLAG, "Print the info-level log messages from the compiler."));

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

  public static void main(String[] args) throws CompilationFailedException, IOException {
    D8Wrapper wrapper = new D8Wrapper();
    String[] remainingArgs = wrapper.parseWrapperArguments(args);
    D8Command.Builder builder = D8Command.parse(
        remainingArgs, CLI_ORIGIN, wrapper.diagnosticsHandler);
    if (builder.isPrintHelp()) {
      System.out.println(getUsageMessage());
      return;
    }
    if (builder.isPrintVersion()) {
      System.out.println("D8(" + WRAPPER_STRING + ") " + Version.getVersionString());
      return;
    }
    wrapper.applyWrapperArguments(builder);
    R8Wrapper.applyCommonCompilerArguments(builder);
    D8.run(builder.build());
  }

  private WrapperDiagnosticsHandler diagnosticsHandler = new WrapperDiagnosticsHandler();
  private boolean printInfoDiagnostics = false;
  private List<Path> noDexArchives = new ArrayList<>();
  private Integer shard_count = null;
  private Integer shard_number = null;

  private String[] parseWrapperArguments(String[] args) {
    List<String> remainingArgs = new ArrayList<>();
    for (int i = 0; i < args.length; i++) {
      String arg = args[i];
      switch (arg) {
        case SHARD_COUNT_FLAG:
        {
          if (++i >= args.length) {
            throw new RuntimeException("Missing argument to " + SHARD_COUNT_FLAG);
          }
          shard_count = Integer.parseInt(args[i]);
          break;
        }
        case SHARD_NUMBER_FLAG:
        {
          if (++i >= args.length) {
            throw new RuntimeException("Missing argument to " + SHARD_NUMBER_FLAG);
          }
          shard_number = Integer.parseInt(args[i]);
          break;
        }
        case INFO_FLAG:
          {
            printInfoDiagnostics = true;
            break;
          }
        case NO_DEX_FLAG:
          {
            if (++i >= args.length) {
              throw new RuntimeException("Missing argument to " + NO_DEX_FLAG);
            }
            Path path = Paths.get(args[i]);
            if (!Files.isRegularFile(path)) {
              throw new RuntimeException("Unexpected argument to " + NO_DEX_FLAG + ". Expected an archive");
            }
            noDexArchives.add(path);
            break;
          }
        default:
          {
            remainingArgs.add(arg);
            break;
          }
      }
    }
    if ((shard_count == null) != (shard_number == null)){
      throw new RuntimeException("You must specify both shard_number and shard_count, or none");
    }
    // We always pass this, but ensure it, the setup of the providers below rely on this.
    if (noDexArchives.isEmpty() && shard_count != null) {
      throw new RuntimeException("no dex archives not used for sharding");
    }
    return remainingArgs.toArray(new String[0]);
  }

  private boolean isProgramEntry(String entry) {
    if (shard_count == null) {
      return ArchiveProgramResourceProvider.includeClassFileEntries(entry);
    }
    return entry.hashCode() % shard_count == shard_number &&
        ArchiveProgramResourceProvider.includeClassFileEntries(entry);
  }

  private boolean isClassPathEntry(String entry) {
    if (shard_count == null) {
      return false;
    }
    return entry.hashCode() % shard_count != shard_number &&
        ArchiveProgramResourceProvider.includeClassFileEntries(entry);
  }

  private void applyWrapperArguments(D8Command.Builder builder) throws IOException {
    diagnosticsHandler.setWarnOnUnsupportedMainDexList(true);
    diagnosticsHandler.setPrintInfoDiagnostics(printInfoDiagnostics);
    for (Path path : noDexArchives) {
      builder.addProgramResourceProvider(
          ArchiveProgramResourceProvider.fromArchive(
              path,
              this::isProgramEntry));
      if (shard_count != null) {
        builder.addClasspathResourceProvider(
            new ArchiveClassFileProvider(path, this::isClassPathEntry));
      }
    }
  }
}
