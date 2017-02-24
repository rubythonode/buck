/*
 * Copyright 2012-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.facebook.buck.rules;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;

import com.facebook.buck.jvm.java.JavaLibraryBuilder;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.step.Step;

import org.easymock.EasyMockSupport;
import org.junit.After;
import org.junit.Test;

import java.util.List;

/**
 * Unit test for {@link ProjectConfig}.
 */
public class ProjectConfigTest extends EasyMockSupport {

  @After
  public void tearDown() {
    verifyAll();
  }

  @Test
  public void testBuildIsIdempotent() throws Exception {
    BuildContext buildContext = FakeBuildContext.NOOP_CONTEXT;
    BuildableContext buildableContext = new FakeBuildableContext();

    ProjectConfig projectConfig = createProjectConfig();
    List<Step> result1 =
        projectConfig.getBuildSteps(buildContext, buildableContext);
    List<Step> result2 =
        projectConfig.getBuildSteps(buildContext, buildableContext);

    assertNotNull("build() should return a non-null result", result1);
    assertSame("build() must be idempotent", result1, result2);
  }

  private ProjectConfig createProjectConfig() throws NoSuchBuildTargetException {
    BuildRuleResolver ruleResolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    BuildRule javaRule = JavaLibraryBuilder
        .createBuilder(BuildTargetFactory.newInstance("//javatests:lib"))
        .build(ruleResolver);

    return ProjectConfigBuilder
        .createBuilder(BuildTargetFactory.newInstance("//javatests:project_config"))
        .setSrcRule(javaRule.getBuildTarget())
        .build(ruleResolver);
  }
}
