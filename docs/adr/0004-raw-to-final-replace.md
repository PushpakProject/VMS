# ADR 0004: Replace via raw → final, then delete raw

**Status:** accepted

## Context
The brief says the watermark service "adds a logo … and replaces it". Overwriting the uploaded object in place is destructive: a bad output or a retry on an already watermarked file would destroy the evidence or add a second logo.

## Options
1. Overwrite the same key.
2. Keep both the original and the watermarked copy forever.
3. Write a new key, then delete the original.

## Decision
Option 3.
1. The upload goes to `raw/{owner}/{id}.webm`.
2. The worker validates its output, then writes `videos/{owner}/{id}.mp4` create-only (`ifGenerationMatch=0`) with metadata `watermarked=true`.
3. It deletes the raw file. A lifecycle rule deletes `raw/` objects after 14 days as a backstop.
4. The API points the video at the final key and marks it READY.

## Consequences
- Nothing is destroyed until the watermarked copy exists.
- Retries and reprocessing always start from the clean original.
- Duplicates are detected by the existence of the final object.
- The end state meets "replace": only the watermarked video remains.
- Reprocessing is impossible once the raw file is gone. The API checks for it and returns 409 `RAW_NOT_AVAILABLE`.
