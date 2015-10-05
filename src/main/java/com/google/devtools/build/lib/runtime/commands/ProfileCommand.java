// Copyright 2014 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.runtime.commands;

import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.collect.Iterables;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.events.EventHandler;
import com.google.devtools.build.lib.profiler.ProfileInfo;
import com.google.devtools.build.lib.profiler.ProfileInfo.InfoListener;
import com.google.devtools.build.lib.profiler.ProfileInfo.Task;
import com.google.devtools.build.lib.profiler.ProfilePhase;
import com.google.devtools.build.lib.profiler.ProfilerTask;
import com.google.devtools.build.lib.profiler.output.HtmlCreator;
import com.google.devtools.build.lib.profiler.output.PhaseText;
import com.google.devtools.build.lib.profiler.statistics.CriticalPathStatistics;
import com.google.devtools.build.lib.profiler.statistics.PhaseStatistics;
import com.google.devtools.build.lib.profiler.statistics.PhaseSummaryStatistics;
import com.google.devtools.build.lib.runtime.BlazeCommand;
import com.google.devtools.build.lib.runtime.BlazeRuntime;
import com.google.devtools.build.lib.runtime.Command;
import com.google.devtools.build.lib.runtime.CommandEnvironment;
import com.google.devtools.build.lib.util.ExitCode;
import com.google.devtools.build.lib.util.StringUtil;
import com.google.devtools.build.lib.util.TimeUtilities;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.common.options.Converters;
import com.google.devtools.common.options.Option;
import com.google.devtools.common.options.OptionsBase;
import com.google.devtools.common.options.OptionsParser;
import com.google.devtools.common.options.OptionsProvider;

import java.io.IOException;
import java.io.PrintStream;
import java.util.EnumMap;
import java.util.regex.Pattern;

/**
 * Command line wrapper for analyzing Blaze build profiles.
 */
@Command(name = "analyze-profile",
         options = { ProfileCommand.ProfileOptions.class },
         shortDescription = "Analyzes build profile data.",
         help = "resource:analyze-profile.txt",
         allowResidue = true,
         completion = "path",
         mustRunInWorkspace = false)
public final class ProfileCommand implements BlazeCommand {

  public static class DumpConverter extends Converters.StringSetConverter {
    public DumpConverter() {
      super("text", "raw", "text-unsorted", "raw-unsorted");
    }
  }

  public static class ProfileOptions extends OptionsBase {
    @Option(
      name = "chart",
      defaultValue = "true",
      help =
          "If --nochart is present, do not include the task chart with --html_details."
              + " The default is --chart."
    )
    public boolean chart;

    @Option(name = "dump",
        abbrev='d',
        converter = DumpConverter.class,
        defaultValue = "null",
        help = "output full profile data dump either in human-readable 'text' format or"
            + " script-friendly 'raw' format, either sorted or unsorted.")
    public String dumpMode;

    @Option(name = "html",
        defaultValue = "false",
        help = "If present, an HTML file visualizing the tasks of the profiled build is created. "
            + "The name of the html file is the name of the profile file plus '.html'.")
    public boolean html;

    @Option(name = "html_pixels_per_second",
        defaultValue = "50",
        help = "Defines the scale of the time axis of the task diagram. The unit is "
            + "pixels per second. Default is 50 pixels per second. ")
    public int htmlPixelsPerSecond;

    @Option(
      name = "html_details",
      defaultValue = "false",
      help =
          "If --html_details is present, the task diagram contains all tasks of the profile "
              + " and performance statistics on user-defined and built-in Skylark functions. "
              + "If --nohtml_details is present, an aggregated diagram is generated. The default "
              + "is to generate an aggregated diagram."
    )
    public boolean htmlDetails;

    @Option(
      name = "html_histograms",
      defaultValue = "false",
      help =
          "If --html_histograms and --html_details is present, the HTML output will display"
              + " histograms for Skylark functions clicked in the statistics table. This will"
              + " increase file size massively."
    )
    public boolean htmlHistograms;

    @Option(
      name = "task_tree",
      defaultValue = "null",
      converter = Converters.RegexPatternConverter.class,
      help =
          "Print the tree of profiler tasks from all tasks matching the given regular expression."
    )
    public Pattern taskTree;

    @Option(
      name = "task_tree_threshold",
      defaultValue = "50",
      help =
          "When printing a task tree, will skip tasks with a duration that is less than the"
              + " given threshold in milliseconds."
    )
    public long taskTreeThreshold;

    @Option(name = "vfs_stats",
        defaultValue = "false",
        help = "If present, include VFS path statistics.")
    public boolean vfsStats;

    @Option(name = "vfs_stats_limit",
        defaultValue = "-1",
        help = "Maximum number of VFS path statistics to print.")
    public int vfsStatsLimit;
  }

  private InfoListener getInfoListener(final CommandEnvironment env) {
    return new InfoListener() {
      private final EventHandler reporter = env.getReporter();

      @Override
      public void info(String text) {
        reporter.handle(Event.info(text));
      }

      @Override
      public void warn(String text) {
        reporter.handle(Event.warn(text));
      }
    };
  }

  @Override
  public void editOptions(CommandEnvironment env, OptionsParser optionsParser) {}

  @Override
  public ExitCode exec(final CommandEnvironment env, OptionsProvider options) {
    final BlazeRuntime runtime = env.getRuntime();
    ProfileOptions opts =
        options.getOptions(ProfileOptions.class);

    if (!opts.vfsStats) {
      opts.vfsStatsLimit = 0;
    }

    try (PrintStream out = new PrintStream(env.getReporter().getOutErr().getOutputStream())) {
      env.getReporter().handle(Event.warn(
          null, "This information is intended for consumption by Blaze developers"
              + " only, and may change at any time.  Script against it at your own risk"));

      for (String name : options.getResidue()) {
        Path profileFile = runtime.getWorkingDirectory().getRelative(name);
        try {
          ProfileInfo info = ProfileInfo.loadProfileVerbosely(
              profileFile, getInfoListener(env));
          ProfileInfo.aggregateProfile(info, getInfoListener(env));

          if (opts.taskTree != null) {
            printTaskTree(out, name, info, opts.taskTree, opts.taskTreeThreshold);
            continue;
          }

          PhaseSummaryStatistics phaseSummaryStatistics = new PhaseSummaryStatistics(info);
          EnumMap<ProfilePhase, PhaseStatistics> phaseStatistics =
              new EnumMap<>(ProfilePhase.class);
          for (ProfilePhase phase : ProfilePhase.values()) {
            phaseStatistics.put(
                phase, new PhaseStatistics(phase, info, runtime.getWorkspaceName()));
          }

          if (opts.dumpMode != null) {
            dumpProfile(env, info, out, opts.dumpMode);
          } else if (opts.html) {
            Path htmlFile =
                profileFile.getParentDirectory().getChild(profileFile.getBaseName() + ".html");

            env.getReporter().handle(Event.info("Creating HTML output in " + htmlFile));

            HtmlCreator.create(
                info,
                htmlFile,
                phaseSummaryStatistics,
                phaseStatistics,
                opts.htmlDetails,
                opts.htmlPixelsPerSecond,
                opts.vfsStatsLimit,
                opts.chart,
                opts.htmlHistograms);
          } else {
            CriticalPathStatistics critPathStats = new CriticalPathStatistics(info);
            new PhaseText(
                    out,
                    phaseSummaryStatistics,
                    phaseStatistics,
                    Optional.of(critPathStats),
                    info.getMissingActionsCount(),
                    opts.vfsStatsLimit)
                .print();
          }
        } catch (IOException e) {
          env.getReporter().handle(Event.error(
              null, "Failed to process file " + name + ": " + e.getMessage()));
        }
      }
    }
    return ExitCode.SUCCESS;
  }

  /**
   * Prints trees rooted at tasks with a description matching a pattern.
   * @see Task#printTaskTree(PrintStream, long)
   */
  private void printTaskTree(
      PrintStream out,
      String fileName,
      ProfileInfo info,
      Pattern taskPattern,
      long taskDurationThreshold) {
    Iterable<Task> tasks = info.findTasksByDescription(taskPattern);
    if (Iterables.isEmpty(tasks)) {
      out.printf("No tasks matching %s found in profile file %s.", taskPattern, fileName);
      out.println();
    } else {
      int skipped = 0;
      for (Task task : tasks) {
        if (!task.printTaskTree(out, taskDurationThreshold)) {
          skipped++;
        }
      }
      if (skipped > 0) {
        out.printf("Skipped %d matching task(s) below the duration threshold.", skipped);
      }
      out.println();
    }
  }

  private void dumpProfile(
      CommandEnvironment env, ProfileInfo info, PrintStream out, String dumpMode) {
    if (!dumpMode.contains("unsorted")) {
      ProfileInfo.aggregateProfile(info, getInfoListener(env));
    }
    if (dumpMode.contains("raw")) {
      for (ProfileInfo.Task task : info.allTasksById) {
        dumpRaw(task, out);
      }
    } else if (dumpMode.contains("unsorted")) {
      for (ProfileInfo.Task task : info.allTasksById) {
        dumpTask(task, out, 0);
      }
    } else {
      for (ProfileInfo.Task task : info.rootTasksById) {
        dumpTask(task, out, 0);
      }
    }
  }

  private void dumpTask(ProfileInfo.Task task, PrintStream out, int indent) {
    StringBuilder builder =
        new StringBuilder(
            String.format(
                Joiner.on('\n')
                    .join(
                        "",
                        "%s %s",
                        "Thread: %-6d  Id: %-6d  Parent: %d",
                        "Start time: %-12s   Duration: %s"),
                task.type,
                task.getDescription(),
                task.threadId,
                task.id,
                task.parentId,
                TimeUtilities.prettyTime(task.startTime),
                TimeUtilities.prettyTime(task.durationNanos)));
    if (task.hasStats()) {
      builder.append("\n");
      ProfileInfo.AggregateAttr[] stats = task.getStatAttrArray();
      for (ProfilerTask type : ProfilerTask.values()) {
        ProfileInfo.AggregateAttr attr = stats[type.ordinal()];
        if (attr != null) {
          builder.append(type.toString().toLowerCase()).append("=(").
              append(attr.count).append(", ").
              append(TimeUtilities.prettyTime(attr.totalTime)).append(") ");
        }
      }
    }
    out.println(StringUtil.indent(builder.toString(), indent));
    for (ProfileInfo.Task subtask : task.subtasks) {
      dumpTask(subtask, out, indent + 1);
    }
  }

  private void dumpRaw(ProfileInfo.Task task, PrintStream out) {
    StringBuilder aggregateString = new StringBuilder();
    ProfileInfo.AggregateAttr[] stats = task.getStatAttrArray();
    for (ProfilerTask type : ProfilerTask.values()) {
      ProfileInfo.AggregateAttr attr = stats[type.ordinal()];
      if (attr != null) {
        aggregateString.append(type.toString().toLowerCase()).append(",").
            append(attr.count).append(",").append(attr.totalTime).append(" ");
      }
    }
    out.println(
        Joiner.on('|')
            .join(
                task.threadId,
                task.id,
                task.parentId,
                task.startTime,
                task.durationNanos,
                aggregateString.toString().trim(),
                task.type,
                task.getDescription()));
  }
}
