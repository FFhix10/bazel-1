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

package com.google.devtools.build.lib.rules.objc;

import static com.google.devtools.build.lib.rules.objc.ObjcProvider.J2OBJC_LIBRARY;
import static com.google.devtools.build.lib.rules.objc.ObjcProvider.MODULE_MAP;
import static com.google.devtools.build.lib.rules.objc.ObjcProvider.SOURCE;
import static com.google.devtools.build.lib.rules.objc.ObjcProvider.UMBRELLA_HEADER;
import static com.google.devtools.build.lib.rules.objc.ObjcRuleClasses.HEADERS;
import static com.google.devtools.build.lib.rules.objc.ObjcRuleClasses.OBJECT_FILE_SOURCES;

import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.analysis.RuleContext;
import com.google.devtools.build.lib.analysis.TransitiveInfoCollection;
import com.google.devtools.build.lib.analysis.config.BuildConfigurationValue;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.rules.apple.AppleToolchain;
import com.google.devtools.build.lib.rules.cpp.CcCompilationContext;
import com.google.devtools.build.lib.rules.cpp.CcInfo;
import com.google.devtools.build.lib.rules.cpp.CcLinkingContext;
import com.google.devtools.build.lib.rules.cpp.CppModuleMap;
import com.google.devtools.build.lib.util.FileType;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.util.ArrayList;
import java.util.List;
import net.starlark.java.eval.StarlarkValue;

/**
 * Contains information common to multiple objc_* rules, and provides a unified API for extracting
 * and accessing it.
 */
// TODO(bazel-team): Decompose and subsume area-specific logic and data into the various *Support
// classes. Make sure to distinguish rule output (providers, runfiles, ...) from intermediate,
// rule-internal information. Any provider created by a rule should not be read, only published.
public final class ObjcCommon implements StarlarkValue {

  /** Filters fileset artifacts out of a group of artifacts. */
  public static ImmutableList<Artifact> filterFileset(Iterable<Artifact> artifacts) {
    ImmutableList.Builder<Artifact> inputs = ImmutableList.<Artifact>builder();
    for (Artifact artifact : artifacts) {
      if (!artifact.isFileset()) {
        inputs.add(artifact);
      }
    }
    return inputs.build();
  }

  /**
   * Indicates the purpose the ObjcCommon is used for.
   *
   * <p>The purpose determines whether ObjcCommon.build() will build an ObjcProvider or an
   * ObjcProvider.Builder. In compile-and-link mode, ObjcCommon.build() will output an
   * ObjcProvider.Builder. The builder is expected to combine with the CcCompilationContext from a
   * compile action, to form a complete ObjcProvider. In link-only mode, ObjcCommon can (and does)
   * output the full ObjcProvider.
   */
  public enum Purpose {
    /** The ObjcCommon will be used for compile and link. */
    COMPILE_AND_LINK,
    /** The ObjcCommon will be used for linking only. */
    LINK_ONLY,
  }

  static class Builder {
    private final Purpose purpose;
    private final RuleContext context;
    private final BuildConfigurationValue buildConfiguration;
    private Optional<CompilationAttributes> compilationAttributes = Optional.absent();
    private Optional<CompilationArtifacts> compilationArtifacts = Optional.absent();
    private Iterable<ObjcProvider> objcProviders = ImmutableList.of();
    private Iterable<PathFragment> includes = ImmutableList.of();
    private IntermediateArtifacts intermediateArtifacts;
    private boolean hasModuleMap;
    private final List<CcCompilationContext> ccCompilationContexts = new ArrayList<>();
    private final List<CcLinkingContext> ccLinkingContexts = new ArrayList<>();
    private final List<CcCompilationContext> directCCompilationContexts = new ArrayList<>();
    private final List<CcCompilationContext> implementationCcCompilationContexts =
        new ArrayList<>();

    private static final ImmutableSet<String> J2OBJC_SUPPORTED_RULES =
        ImmutableSet.of("java_import", "java_library", "java_proto_library", "proto_library");

    /**
     * Builder for {@link ObjcCommon} obtaining both attribute data and configuration data from the
     * given rule context.
     */
    Builder(Purpose purpose, RuleContext context) throws InterruptedException {
      this(purpose, context, context.getConfiguration());
    }

    /**
     * Builder for {@link ObjcCommon} obtaining attribute data from the rule context and
     * configuration data from the given configuration object for use in situations where a single
     * target's outputs are under multiple configurations.
     */
    Builder(Purpose purpose, RuleContext context, BuildConfigurationValue buildConfiguration)
        throws InterruptedException {
      this.purpose = purpose;
      this.context = Preconditions.checkNotNull(context);
      this.buildConfiguration = Preconditions.checkNotNull(buildConfiguration);
    }

    @CanIgnoreReturnValue
    public Builder setCompilationAttributes(CompilationAttributes baseCompilationAttributes) {
      Preconditions.checkState(
          !this.compilationAttributes.isPresent(),
          "compilationAttributes is already set to: %s",
          this.compilationAttributes);
      this.compilationAttributes = Optional.of(baseCompilationAttributes);
      return this;
    }

    @CanIgnoreReturnValue
    Builder setCompilationArtifacts(CompilationArtifacts compilationArtifacts) {
      Preconditions.checkState(
          !this.compilationArtifacts.isPresent(),
          "compilationArtifacts is already set to: %s",
          this.compilationArtifacts);
      this.compilationArtifacts = Optional.of(compilationArtifacts);
      return this;
    }

    @CanIgnoreReturnValue
    Builder addDirectCcCompilationContexts(Iterable<CcInfo> ccInfos) {
      // TODO(waltl): Support direct CcCompilationContexts in CcCompilationHelper.
      Preconditions.checkState(
          this.purpose.equals(Purpose.LINK_ONLY),
          "direct CcCompilationContext is only supported for LINK_ONLY purpose");
      ccInfos.forEach(ccInfo -> directCCompilationContexts.add(ccInfo.getCcCompilationContext()));
      return this;
    }

    @CanIgnoreReturnValue
    Builder addCcCompilationContexts(Iterable<CcInfo> ccInfos) {
      ccInfos.forEach(ccInfo -> ccCompilationContexts.add(ccInfo.getCcCompilationContext()));
      return this;
    }

    @CanIgnoreReturnValue
    Builder addCcLinkingContexts(Iterable<CcInfo> ccInfos) {
      ccInfos.forEach(ccInfo -> ccLinkingContexts.add(ccInfo.getCcLinkingContext()));
      return this;
    }

    @CanIgnoreReturnValue
    Builder addImplementationCcCompilationContexts(Iterable<CcInfo> ccInfos) {
      ccInfos.forEach(
          ccInfo -> implementationCcCompilationContexts.add(ccInfo.getCcCompilationContext()));
      return this;
    }

    @CanIgnoreReturnValue
    Builder addCcInfos(Iterable<CcInfo> ccInfos) {
      addCcCompilationContexts(ccInfos);
      addCcLinkingContexts(ccInfos);
      return this;
    }

    @CanIgnoreReturnValue
    Builder addDeps(List<? extends TransitiveInfoCollection> deps) {
      ImmutableList.Builder<ObjcProvider> objcProviders = ImmutableList.builder();
      ImmutableList.Builder<CcInfo> ccInfos = ImmutableList.builder();

      for (TransitiveInfoCollection dep : deps) {
        ObjcProvider objcProvider = dep.get(ObjcProvider.STARLARK_CONSTRUCTOR);
        if (objcProvider != null) {
          objcProviders.add(objcProvider);
        }
        CcInfo ccInfo = dep.get(CcInfo.PROVIDER);
        if (ccInfo != null) {
          ccInfos.add(ccInfo);
        }
      }

      addObjcProviders(objcProviders.build());
      addCcInfos(ccInfos.build());

      return this;
    }

    /**
     * Add providers which will be exposed both to the declaring rule and to any dependers on the
     * declaring rule.
     */
    @CanIgnoreReturnValue
    Builder addObjcProviders(Iterable<ObjcProvider> objcProviders) {
      this.objcProviders = Iterables.concat(this.objcProviders, objcProviders);
      return this;
    }

    /** Adds includes to be passed into compile actions with {@code -I}. */
    @CanIgnoreReturnValue
    public Builder addIncludes(NestedSet<PathFragment> includes) {
      // The includes are copied to a new list in the .build() method, so flattening here should be
      // benign.
      this.includes = Iterables.concat(this.includes, includes.toList());
      return this;
    }

    /** Adds includes to be passed into compile actions with {@code -I}. */
    @CanIgnoreReturnValue
    public Builder addIncludes(Iterable<PathFragment> includes) {
      this.includes = Iterables.concat(this.includes, includes);
      return this;
    }

    @CanIgnoreReturnValue
    Builder setIntermediateArtifacts(IntermediateArtifacts intermediateArtifacts) {
      this.intermediateArtifacts = intermediateArtifacts;
      return this;
    }

    /**
     * Specifies that this target has a clang module map. This should be called if this target
     * compiles sources or exposes headers for other targets to use. Note that this does not add the
     * action to generate the module map. It simply indicates that it should be added to the
     * provider.
     */
    @CanIgnoreReturnValue
    Builder setHasModuleMap() {
      this.hasModuleMap = true;
      return this;
    }

    ObjcCommon build() {
      ImmutableList<CcCompilationContext> ccCompilationContexts =
          ImmutableList.copyOf(this.ccCompilationContexts);
      ImmutableList<CcLinkingContext> ccLinkingContexts =
          ImmutableList.copyOf(this.ccLinkingContexts);
      ImmutableList<CcCompilationContext> directCCompilationContexts =
          ImmutableList.copyOf(this.directCCompilationContexts);
      ImmutableList<CcCompilationContext> implementationCcCompilationContexts =
          ImmutableList.copyOf(this.implementationCcCompilationContexts);

      ObjcCompilationContext.Builder objcCompilationContextBuilder =
          ObjcCompilationContext.builder();

      ObjcProvider.Builder objcProvider = new ObjcProvider.Builder();

      objcProvider
          .addTransitiveAndPropagate(objcProviders);

      objcCompilationContextBuilder
          .addIncludes(includes)
          .addObjcProviders(objcProviders)
          .addDirectCcCompilationContexts(directCCompilationContexts)
          // TODO(bazel-team): This pulls in stl via
          // CcCompilationHelper.getStlCcCompilationContext(), but probably shouldn't.
          .addCcCompilationContexts(ccCompilationContexts)
          .addImplementationCcCompilationContexts(implementationCcCompilationContexts);

      if (compilationAttributes.isPresent()) {
        CompilationAttributes attributes = compilationAttributes.get();
        PathFragment usrIncludeDir = PathFragment.create(AppleToolchain.sdkDir() + "/usr/include/");
        Iterable<PathFragment> sdkIncludes =
            Iterables.transform(
                attributes.sdkIncludes().toList(), (p) -> usrIncludeDir.getRelative(p));
        objcCompilationContextBuilder
            .addPublicHeaders(filterFileset(attributes.hdrs().toList()))
            .addPublicTextualHeaders(filterFileset(attributes.textualHdrs().toList()))
            .addDefines(attributes.defines())
            .addIncludes(
                attributes
                    .headerSearchPaths(
                        buildConfiguration.getGenfilesFragment(context.getRepository()))
                    .toList())
            .addIncludes(sdkIncludes);
      }

      for (CompilationArtifacts artifacts : compilationArtifacts.asSet()) {
        Iterable<Artifact> allSources =
            Iterables.concat(
                FileType.except(artifacts.getSrcs(), OBJECT_FILE_SOURCES),
                artifacts.getNonArcSrcs());
        objcProvider
            .addAll(SOURCE, allSources)
            .addAllDirect(SOURCE, allSources);
        objcCompilationContextBuilder.addPublicHeaders(
            filterFileset(artifacts.getAdditionalHdrs()));
        objcCompilationContextBuilder.addPrivateHeaders(
            FileType.filter(artifacts.getSrcs(), HEADERS));

        if (artifacts.getArchive().isPresent()
            && J2OBJC_SUPPORTED_RULES.contains(context.getRule().getRuleClass())) {
          objcProvider.addAll(J2OBJC_LIBRARY, artifacts.getArchive().asSet());
        }
      }

      if (hasModuleMap) {
        CppModuleMap moduleMap = intermediateArtifacts.swiftModuleMap();
        Optional<Artifact> umbrellaHeader = moduleMap.getUmbrellaHeader();
        if (umbrellaHeader.isPresent()) {
          objcProvider.add(UMBRELLA_HEADER, umbrellaHeader.get());
        }
        objcProvider.add(MODULE_MAP, moduleMap.getArtifact());
        objcProvider.addDirect(MODULE_MAP, moduleMap.getArtifact());
      }

      ObjcCompilationContext objcCompilationContext = objcCompilationContextBuilder.build();

      return new ObjcCommon(
          purpose,
          objcProvider.build(),
          objcCompilationContext,
          ccLinkingContexts,
          compilationArtifacts);
    }
  }

  private final Purpose purpose;
  private final ObjcProvider objcProvider;
  private final ObjcCompilationContext objcCompilationContext;
  private final ImmutableList<CcLinkingContext> ccLinkingContexts;

  private final Optional<CompilationArtifacts> compilationArtifacts;

  private ObjcCommon(
      Purpose purpose,
      ObjcProvider objcProvider,
      ObjcCompilationContext objcCompilationContext,
      ImmutableList<CcLinkingContext> ccLinkingContexts,
      Optional<CompilationArtifacts> compilationArtifacts) {
    this.purpose = purpose;
    this.objcProvider = Preconditions.checkNotNull(objcProvider);
    this.objcCompilationContext = Preconditions.checkNotNull(objcCompilationContext);
    this.ccLinkingContexts = Preconditions.checkNotNull(ccLinkingContexts);
    this.compilationArtifacts = Preconditions.checkNotNull(compilationArtifacts);
  }

  public Purpose getPurpose() {
    return purpose;
  }

  public ObjcProvider getObjcProvider() {
    return objcProvider;
  }

  public ObjcCompilationContext getObjcCompilationContext() {
    return objcCompilationContext;
  }

  public ImmutableList<CcLinkingContext> getCcLinkingContexts() {
    return ccLinkingContexts;
  }

  public Optional<CompilationArtifacts> getCompilationArtifacts() {
    return compilationArtifacts;
  }

  public CcCompilationContext createCcCompilationContext() {
    return objcCompilationContext.createCcCompilationContext();
  }

  public CcLinkingContext createCcLinkingContext() {
    return CcLinkingContext.merge(ccLinkingContexts);
  }

  public CcInfo createCcInfo() {
    return CcInfo.builder()
        .setCcCompilationContext(createCcCompilationContext())
        .setCcLinkingContext(createCcLinkingContext())
        .build();
  }

  /**
   * Returns an {@link Optional} containing the compiled {@code .a} file, or {@link
   * Optional#absent()} if this object contains no {@link CompilationArtifacts} or the compilation
   * information has no sources.
   */
  public Optional<Artifact> getCompiledArchive() {
    if (compilationArtifacts.isPresent()) {
      return compilationArtifacts.get().getArchive();
    }
    return Optional.absent();
  }
}
