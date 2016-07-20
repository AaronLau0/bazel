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

package com.google.devtools.build.lib.rules.filegroup;

import static com.google.devtools.build.lib.analysis.OutputGroupProvider.INTERNAL_SUFFIX;

import com.google.devtools.build.lib.actions.Actions;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.analysis.CompilationHelper;
import com.google.devtools.build.lib.analysis.ConfiguredTarget;
import com.google.devtools.build.lib.analysis.MiddlemanProvider;
import com.google.devtools.build.lib.analysis.OutputGroupProvider;
import com.google.devtools.build.lib.analysis.RuleConfiguredTarget.Mode;
import com.google.devtools.build.lib.analysis.RuleConfiguredTargetBuilder;
import com.google.devtools.build.lib.analysis.RuleContext;
import com.google.devtools.build.lib.analysis.Runfiles;
import com.google.devtools.build.lib.analysis.RunfilesProvider;
import com.google.devtools.build.lib.analysis.TransitiveInfoCollection;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.collect.nestedset.Order;
import com.google.devtools.build.lib.rules.RuleConfiguredTargetFactory;
import com.google.devtools.build.lib.rules.test.InstrumentedFilesCollector;
import com.google.devtools.build.lib.rules.test.InstrumentedFilesCollector.InstrumentationSpec;
import com.google.devtools.build.lib.rules.test.InstrumentedFilesProvider;
import com.google.devtools.build.lib.syntax.Type;
import com.google.devtools.build.lib.util.FileTypeSet;
import com.google.devtools.build.lib.vfs.PathFragment;

import java.util.Iterator;
import java.util.List;

/**
 * ConfiguredTarget for "filegroup".
 */
public class Filegroup implements RuleConfiguredTargetFactory {

  /** Error message for output groups that are explicitly blacklisted for filegroup reference. */
  public static final String ILLEGAL_OUTPUT_GROUP_ERROR =
      "Output group %s is not permitted for " + "reference in filegroups.";

  @Override
  public ConfiguredTarget create(RuleContext ruleContext) throws RuleErrorException {
    String outputGroupName = ruleContext.attributes().get("output_group", Type.STRING);

    if (outputGroupName.endsWith(INTERNAL_SUFFIX)) {
      ruleContext.throwWithAttributeError(
          "output_group", String.format(ILLEGAL_OUTPUT_GROUP_ERROR, outputGroupName));
    }

    NestedSet<Artifact> filesToBuild =
        outputGroupName.isEmpty()
            ? NestedSetBuilder.wrap(
                Order.STABLE_ORDER,
                ruleContext.getPrerequisiteArtifacts("srcs", Mode.TARGET).list())
            : getArtifactsForOutputGroup(
                outputGroupName, ruleContext.getPrerequisites("srcs", Mode.TARGET));

    NestedSet<Artifact> middleman =
        CompilationHelper.getAggregatingMiddleman(
            ruleContext, Actions.escapeLabel(ruleContext.getLabel()), filesToBuild);

    InstrumentedFilesProvider instrumentedFilesProvider =
        InstrumentedFilesCollector.collect(ruleContext,
            // what do *we* know about whether this is a source file or not
            new InstrumentationSpec(FileTypeSet.ANY_FILE, "srcs", "deps", "data"),
            InstrumentedFilesCollector.NO_METADATA_COLLECTOR, filesToBuild);

    RunfilesProvider runfilesProvider = RunfilesProvider.withData(
        new Runfiles.Builder(
            ruleContext.getWorkspaceName(),
            ruleContext.getConfiguration().legacyExternalRunfiles())
            .addRunfiles(ruleContext, RunfilesProvider.DEFAULT_RUNFILES)
            .build(),
        // If you're visiting a filegroup as data, then we also visit its data as data.
        new Runfiles.Builder(
            ruleContext.getWorkspaceName(),
            ruleContext.getConfiguration().legacyExternalRunfiles())
            .addTransitiveArtifacts(filesToBuild)
            .addDataDeps(ruleContext).build());

    return new RuleConfiguredTargetBuilder(ruleContext)
        .add(RunfilesProvider.class, runfilesProvider)
        .setFilesToBuild(filesToBuild)
        .setRunfilesSupport(null, getExecutable(filesToBuild))
        .add(InstrumentedFilesProvider.class, instrumentedFilesProvider)
        .add(MiddlemanProvider.class, new MiddlemanProvider(middleman))
        .add(FilegroupPathProvider.class,
            new FilegroupPathProvider(getFilegroupPath(ruleContext)))
        .build();
  }

  /**
   * Returns the single Artifact from filesToBuild or {@code null} if there are multiple elements.
   */
  private Artifact getExecutable(NestedSet<Artifact> filesToBuild) {
    Iterator<Artifact> it = filesToBuild.iterator();
    if (it.hasNext()) {
      Artifact out = it.next();
      if (!it.hasNext()) {
        return out;
      }
    }
    return null;
  }

  private PathFragment getFilegroupPath(RuleContext ruleContext) {
    String attr = ruleContext.attributes().get("path", Type.STRING);
    if (attr.isEmpty()) {
      return PathFragment.EMPTY_FRAGMENT;
    } else {
      return ruleContext.getLabel().getPackageIdentifier().getPathFragment().getRelative(attr);
    }
  }

  /** Returns the artifacts from the given targets that are members of the given output group. */
  private static NestedSet<Artifact> getArtifactsForOutputGroup(
      String outputGroupName, List<? extends TransitiveInfoCollection> deps) {
    NestedSetBuilder<Artifact> result = NestedSetBuilder.stableOrder();

    for (TransitiveInfoCollection dep : deps) {
      OutputGroupProvider outputGroupProvider = dep.getProvider(OutputGroupProvider.class);
      if (outputGroupProvider != null) {
        result.addTransitive(outputGroupProvider.getOutputGroup(outputGroupName));
      }
    }

    return result.build();
  }
}
