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

package com.facebook.buck.step.fs;

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.util.Escaper;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Command that runs equivalent command of {@code mkdir -p} on the specified directory.
 */
public class MkdirStep implements Step {

  private final ProjectFilesystem filesystem;
  private final Path pathRelativeToProjectRoot;

  public MkdirStep(ProjectFilesystem filesystem, Path pathRelativeToProjectRootOrJustAbsolute) {
    this.filesystem = filesystem;
    this.pathRelativeToProjectRoot = pathRelativeToProjectRootOrJustAbsolute;
  }

  @Override
  public StepExecutionResult execute(ExecutionContext context) {
    try {
      filesystem.mkdirs(pathRelativeToProjectRoot);
    } catch (IOException e) {
      context.logError(e, "Cannot make directories: %s", pathRelativeToProjectRoot);
      return StepExecutionResult.ERROR;
    }
    return StepExecutionResult.SUCCESS;
  }

  @Override
  public String getShortName() {
    return "mkdir";
  }

  @Override
  public String getDescription(ExecutionContext context) {
    return String.format("mkdir -p %s",
        Escaper.escapeAsShellString(getPath().toString()));
  }

  /**
   * Get the path of the directory to make.
   * @return Path of the directory to make.
   */
  public Path getPath() {
    return filesystem.resolve(pathRelativeToProjectRoot);
  }

  @Override
  public boolean equals(Object obj) {
    if (!(obj instanceof MkdirStep)) {
      return false;
    }
    return getPath().equals(((MkdirStep) obj).getPath());
  }

  @Override
  public int hashCode() {
    return getPath().hashCode();
  }
}
