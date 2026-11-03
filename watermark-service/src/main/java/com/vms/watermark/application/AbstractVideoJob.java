package com.vms.watermark.application;

import java.nio.file.Path;
import java.util.Optional;

/**
 * Template Method: a fixed, safe pipeline for any video transformation. Subclasses fill in the
 * steps; the ordering, idempotency check and cleanup cannot be forgotten.
 *
 * <ol>
 *   <li>skip if the output already exists (duplicate / redelivered message)
 *   <li>fetch the source into a temp workspace
 *   <li>validate the input
 *   <li>transform
 *   <li>verify the output
 *   <li>store the output (create-only)
 *   <li>remove the source ("replace")
 *   <li>always delete the workspace
 * </ol>
 */
public abstract class AbstractVideoJob<I> {

  public final JobResult execute(JobRequest request) {
    Optional<JobResult> existing = findCompletedOutput(request);
    if (existing.isPresent()) {
      removeSource(request);
      return existing.get();
    }
    try (TempWorkspace workspace = TempWorkspace.create(workDir(), request.videoId().toString())) {
      Path input = fetchSource(request, workspace);
      I inputInfo = validateInput(request, input, workspace);
      Path output = transform(request, input, inputInfo, workspace);
      I outputInfo = verifyOutput(request, output, inputInfo, workspace);
      JobResult result = storeOutput(request, output, outputInfo);
      removeSource(request);
      return result;
    }
  }

  protected abstract Path workDir();

  protected abstract Optional<JobResult> findCompletedOutput(JobRequest request);

  protected abstract Path fetchSource(JobRequest request, TempWorkspace workspace);

  protected abstract I validateInput(JobRequest request, Path input, TempWorkspace workspace);

  protected abstract Path transform(
      JobRequest request, Path input, I inputInfo, TempWorkspace workspace);

  protected abstract I verifyOutput(
      JobRequest request, Path output, I inputInfo, TempWorkspace workspace);

  protected abstract JobResult storeOutput(JobRequest request, Path output, I outputInfo);

  protected abstract void removeSource(JobRequest request);
}
