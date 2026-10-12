-- Solus Chat Responsiveness and Frame Deadline Analysis
-- Analyzes main thread execution, missed frame deadlines, draw passes, and inference slice durations.

-- 1. Main thread slices exceeding 50ms during active chat sessions
SELECT
    slice.name AS slice_name,
    thread.name AS thread_name,
    process.name AS process_name,
    slice.ts / 1e6 AS start_ms,
    slice.dur / 1e6 AS dur_ms
FROM slice
JOIN thread_track ON slice.track_id = thread_track.id
JOIN thread USING (utid)
JOIN process USING (upid)
WHERE process.name = 'com.shounak.localmeshai'
  AND (thread.name = 'com.shounak.localmeshai' OR thread.name = 'main')
  AND slice.dur > 50 * 1e6
ORDER BY slice.dur DESC
LIMIT 50;

-- 2. SurfaceFlinger frame timeline: jank and missed frame deadlines
SELECT
    jank_type,
    present_type,
    count() AS frame_count,
    avg(dur) / 1e6 AS avg_dur_ms,
    max(dur) / 1e6 AS max_dur_ms
FROM actual_frame_timeline_slice
JOIN process USING (upid)
WHERE process.name = 'com.shounak.localmeshai'
GROUP BY jank_type, present_type
ORDER BY frame_count DESC;

-- 3. Application ATrace spans for Solus presentation and inference timing
SELECT
    slice.name AS span_name,
    count() AS count,
    avg(slice.dur) / 1e6 AS avg_ms,
    min(slice.dur) / 1e6 AS min_ms,
    max(slice.dur) / 1e6 AS max_ms
FROM slice
JOIN thread_track ON slice.track_id = thread_track.id
JOIN thread USING (utid)
JOIN process USING (upid)
WHERE process.name = 'com.shounak.localmeshai'
  AND (
    slice.name LIKE 'MessagePresentation%' OR
    slice.name LIKE 'StreamingResponsePublisher%' OR
    slice.name LIKE 'InferenceResponseController%' OR
    slice.name LIKE 'ModelLoadController%' OR
    slice.name LIKE 'ModelRuntimeCoordinator%' OR
    slice.name LIKE 'LlamaCppEngine%' OR
    slice.name LIKE 'LiteRtStreamAdapter%' OR
    slice.name LIKE 'ChatInferenceManager%'
  )
GROUP BY slice.name
ORDER BY avg_ms DESC;

-- 4. Frames with draw or doFrame exceeding 100ms
SELECT
    slice.name AS frame_slice,
    slice.ts / 1e6 AS start_ms,
    slice.dur / 1e6 AS dur_ms
FROM slice
JOIN thread_track ON slice.track_id = thread_track.id
JOIN thread USING (utid)
JOIN process USING (upid)
WHERE process.name = 'com.shounak.localmeshai'
  AND (slice.name = 'Choreographer#doFrame' OR slice.name LIKE '%draw%' OR slice.name LIKE '%Layout%')
  AND slice.dur > 100 * 1e6
ORDER BY slice.dur DESC;
