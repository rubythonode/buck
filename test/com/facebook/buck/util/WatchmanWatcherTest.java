/*
 * Copyright 2013-present Facebook, Inc.
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

package com.facebook.buck.util;

import static org.easymock.EasyMock.anyObject;
import static org.easymock.EasyMock.capture;
import static org.easymock.EasyMock.createStrictMock;
import static org.easymock.EasyMock.newCapture;
import static org.easymock.EasyMock.replay;
import static org.easymock.EasyMock.verify;
import static org.hamcrest.Matchers.hasEntry;
import static org.hamcrest.Matchers.hasItem;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.facebook.buck.event.BuckEvent;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.BuckEventBusFactory;
import com.facebook.buck.event.WatchmanStatusEvent;
import com.facebook.buck.io.FakeWatchmanClient;
import com.facebook.buck.io.MorePaths;
import com.facebook.buck.io.PathOrGlobMatcher;
import com.facebook.buck.io.ProjectWatch;
import com.facebook.buck.io.Watchman;
import com.facebook.buck.io.WatchmanCursor;
import com.facebook.buck.io.WatchmanDiagnostic;
import com.facebook.buck.io.WatchmanDiagnosticEvent;
import com.facebook.buck.io.WatchmanDiagnosticEventListener;
import com.facebook.buck.io.WatchmanQuery;
import com.facebook.buck.timing.FakeClock;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import com.google.common.eventbus.EventBus;
import com.google.common.eventbus.Subscribe;

import org.easymock.Capture;
import org.hamcrest.Matchers;
import org.junit.After;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public class WatchmanWatcherTest {

  private static final Path FAKE_ROOT = Paths.get("/fake/root").toAbsolutePath();
  private static final WatchmanQuery FAKE_QUERY =
      WatchmanQuery.of("/fake/root", ImmutableMap.of());
  private static final List<Object> FAKE_UUID_QUERY = FAKE_QUERY.toList("n:buckduuid");
  private static final List<Object> FAKE_CLOCK_QUERY = FAKE_QUERY.toList("c:0:0");

  private static final Path FAKE_SECONDARY_ROOT = Paths.get("/fake/secondary").toAbsolutePath();
  private static final WatchmanQuery FAKE_SECONDARY_QUERY =
      WatchmanQuery.of("/fake/SECONDARY", ImmutableMap.of());

  @After
  public void cleanUp() {
    // Clear interrupted state so it doesn't affect any other test.
    Thread.interrupted();
  }

  @Test
  public void whenFilesListIsEmptyThenNoEventsAreGenerated()
    throws IOException, InterruptedException {
    ImmutableMap<String, Object> watchmanOutput = ImmutableMap.of(
        "version", "2.9.2",
        "clock", "c:1386170113:26390:5:50273",
        "is_fresh_instance", false,
        "files", ImmutableList.of());
    EventBus eventBus = createStrictMock(EventBus.class);
    replay(eventBus);
    WatchmanWatcher watcher = createWatcher(
        eventBus,
        watchmanOutput);
    watcher.postEvents(
        BuckEventBusFactory.newInstance(new FakeClock(0)),
        WatchmanWatcher.FreshInstanceAction.NONE);
    verify(eventBus);
  }

  @Test
  public void whenNameThenModifyEventIsGenerated() throws IOException, InterruptedException {
    ImmutableMap<String, Object> watchmanOutput = ImmutableMap.of(
        "files", ImmutableList.of(
            ImmutableMap.<String, Object>of("name", "foo/bar/baz")));
    Capture<WatchEvent<Path>> eventCapture = newCapture();
    EventBus eventBus = createStrictMock(EventBus.class);
    eventBus.post(capture(eventCapture));
    replay(eventBus);
    WatchmanWatcher watcher = createWatcher(
        eventBus,
        watchmanOutput);
    watcher.postEvents(
        BuckEventBusFactory.newInstance(new FakeClock(0)),
        WatchmanWatcher.FreshInstanceAction.NONE);
    verify(eventBus);
    assertEquals("Should be modify event.",
        StandardWatchEventKinds.ENTRY_MODIFY,
        eventCapture.getValue().kind());
    assertEquals("Path should match watchman output.",
        MorePaths.pathWithPlatformSeparators("foo/bar/baz"),
        eventCapture.getValue().context().toString());
  }

  @Test
  public void whenNewIsTrueThenCreateEventIsGenerated() throws IOException, InterruptedException {
    ImmutableMap<String, Object> watchmanOutput = ImmutableMap.of(
        "files", ImmutableList.of(
            ImmutableMap.<String, Object>of(
                "name", "foo/bar/baz",
                "new", true)));
    Capture<WatchEvent<Path>> eventCapture = newCapture();
    EventBus eventBus = createStrictMock(EventBus.class);
    eventBus.post(capture(eventCapture));
    replay(eventBus);
    WatchmanWatcher watcher = createWatcher(
        eventBus,
        watchmanOutput);
    watcher.postEvents(
        BuckEventBusFactory.newInstance(new FakeClock(0)),
        WatchmanWatcher.FreshInstanceAction.NONE);
    verify(eventBus);
    assertEquals("Should be create event.",
        StandardWatchEventKinds.ENTRY_CREATE,
        eventCapture.getValue().kind());
  }

  @Test
  public void whenExistsIsFalseThenDeleteEventIsGenerated()
    throws IOException, InterruptedException {
    ImmutableMap<String, Object> watchmanOutput = ImmutableMap.of(
        "files", ImmutableList.of(
            ImmutableMap.<String, Object>of(
                "name", "foo/bar/baz",
                "exists", false)));
    Capture<WatchEvent<Path>> eventCapture = newCapture();
    EventBus eventBus = createStrictMock(EventBus.class);
    eventBus.post(capture(eventCapture));
    replay(eventBus);
    WatchmanWatcher watcher = createWatcher(
        eventBus,
        watchmanOutput);
    watcher.postEvents(
        BuckEventBusFactory.newInstance(new FakeClock(0)),
        WatchmanWatcher.FreshInstanceAction.NONE);
    verify(eventBus);
    assertEquals("Should be delete event.",
        StandardWatchEventKinds.ENTRY_DELETE,
        eventCapture.getValue().kind());
  }

  @Test
  public void whenNewAndNotExistsThenDeleteEventIsGenerated()
      throws IOException, InterruptedException {
    ImmutableMap<String, Object> watchmanOutput = ImmutableMap.of(
        "files", ImmutableList.of(
            ImmutableMap.<String, Object>of(
                "name", "foo/bar/baz",
                "new", true,
                "exists", false)));
    Capture<WatchEvent<Path>> eventCapture = newCapture();
    EventBus eventBus = createStrictMock(EventBus.class);
    eventBus.post(capture(eventCapture));
    replay(eventBus);
    WatchmanWatcher watcher = createWatcher(
        eventBus,
        watchmanOutput);
    watcher.postEvents(
        BuckEventBusFactory.newInstance(new FakeClock(0)),
        WatchmanWatcher.FreshInstanceAction.NONE);
    verify(eventBus);
    assertEquals("Should be delete event.",
        StandardWatchEventKinds.ENTRY_DELETE,
        eventCapture.getValue().kind());
  }

  @Test
  public void whenMultipleFilesThenMultipleEventsGenerated()
      throws IOException, InterruptedException {
    ImmutableMap<String, Object> watchmanOutput = ImmutableMap.of(
        "files", ImmutableList.of(
            ImmutableMap.<String, Object>of("name", "foo/bar/baz"),
            ImmutableMap.<String, Object>of("name", "foo/bar/boz")));
    EventBus eventBus = createStrictMock(EventBus.class);
    Capture<WatchEvent<Path>> firstEvent = newCapture();
    Capture<WatchEvent<Path>> secondEvent = newCapture();
    eventBus.post(capture(firstEvent));
    eventBus.post(capture(secondEvent));
    replay(eventBus);
    WatchmanWatcher watcher = createWatcher(
        eventBus,
        watchmanOutput);
    watcher.postEvents(
        BuckEventBusFactory.newInstance(new FakeClock(0)),
        WatchmanWatcher.FreshInstanceAction.NONE);
    verify(eventBus);
    assertEquals("Path should match watchman output.",
        MorePaths.pathWithPlatformSeparators("foo/bar/baz"),
        firstEvent.getValue().context().toString());
    assertEquals("Path should match watchman output.",
        MorePaths.pathWithPlatformSeparators("foo/bar/boz"),
        secondEvent.getValue().context().toString());
  }

  @Test
  public void whenWatchmanFailsThenOverflowEventGenerated()
      throws IOException, InterruptedException {
    Capture<WatchEvent<Path>> eventCapture = newCapture();
    EventBus eventBus = createStrictMock(EventBus.class);
    eventBus.post(capture(eventCapture));
    replay(eventBus);
    WatchmanWatcher watcher = createWatcher(
        eventBus,
        new FakeWatchmanClient(
            0 /* queryElapsedTimeNanos */,
            ImmutableMap.of(FAKE_UUID_QUERY, ImmutableMap.of()),
            new IOException("oops")),
        10000 /* timeout */);
    try {
      watcher.postEvents(
          BuckEventBusFactory.newInstance(new FakeClock(0)),
          WatchmanWatcher.FreshInstanceAction.NONE);
      fail("Should have thrown IOException.");
    } catch (IOException e) {
      assertTrue("Should be expected error", e.getMessage().startsWith("oops"));
    }
    verify(eventBus);
    assertEquals("Should be overflow event.",
        StandardWatchEventKinds.OVERFLOW,
        eventCapture.getValue().kind());
  }

  @Test
  public void whenWatchmanInterruptedThenOverflowEventGenerated()
      throws IOException, InterruptedException {
    String message = "Boo!";
    Capture<WatchEvent<Path>> eventCapture = newCapture();
    EventBus eventBus = createStrictMock(EventBus.class);
    eventBus.post(capture(eventCapture));
    replay(eventBus);
    WatchmanWatcher watcher = createWatcher(
        eventBus,
        new FakeWatchmanClient(
            0 /* queryElapsedTimeNanos */,
            ImmutableMap.of(FAKE_UUID_QUERY, ImmutableMap.of()),
            new InterruptedException(message)),
        10000 /* timeout */);
    try {
      watcher.postEvents(
          BuckEventBusFactory.newInstance(new FakeClock(0)),
          WatchmanWatcher.FreshInstanceAction.NONE);
    } catch (InterruptedException e) {
      assertEquals("Should be test interruption.", e.getMessage(), message);
    }
    verify(eventBus);
    assertTrue(Thread.currentThread().isInterrupted());
    assertEquals("Should be overflow event.",
        StandardWatchEventKinds.OVERFLOW,
        eventCapture.getValue().kind());
  }

  @Test
  public void whenQueryResultContainsErrorThenHumanReadableExceptionThrown()
      throws IOException, InterruptedException {
    String watchmanError = "Watch does not exist.";
    ImmutableMap<String, Object> watchmanOutput = ImmutableMap.of(
        "version", "2.9.2",
        "error", watchmanError);
    EventBus eventBus = createStrictMock(EventBus.class);
    eventBus.post(anyObject());
    replay(eventBus);
    WatchmanWatcher watcher = createWatcher(
        eventBus,
        watchmanOutput);
    try {
      watcher.postEvents(
          BuckEventBusFactory.newInstance(new FakeClock(0)),
          WatchmanWatcher.FreshInstanceAction.NONE);
      fail("Should have thrown RuntimeException");
    } catch (RuntimeException e) {
      assertThat("Should contain watchman error.",
          e.getMessage(),
          Matchers.containsString(watchmanError));
    }
  }

  @Test(expected = WatchmanWatcherException.class)
  public void whenQueryResultContainsErrorThenOverflowEventGenerated()
      throws IOException, InterruptedException {
    ImmutableMap<String, Object> watchmanOutput = ImmutableMap.of(
        "version", "2.9.2",
        "error", "Watch does not exist.");
    Capture<WatchEvent<Path>> eventCapture = newCapture();
    EventBus eventBus = createStrictMock(EventBus.class);
    eventBus.post(capture(eventCapture));
    replay(eventBus);
    WatchmanWatcher watcher = createWatcher(
        eventBus,
        watchmanOutput);
    try {
      watcher.postEvents(
          BuckEventBusFactory.newInstance(new FakeClock(0)),
          WatchmanWatcher.FreshInstanceAction.NONE);
    } finally {
      assertEquals("Should be overflow event.",
        StandardWatchEventKinds.OVERFLOW,
        eventCapture.getValue().kind());
    }
  }

  @Test
  public void whenWatchmanInstanceIsFreshAndActionIsPostThenAllCachesAreCleared()
      throws IOException, InterruptedException {
    ImmutableMap<String, Object> watchmanOutput = ImmutableMap.of(
        "version", "2.9.2",
        "clock", "c:1386170113:26390:5:50273",
        "is_fresh_instance", true,
        "files", ImmutableList.of());

    final Set<WatchEvent<?>> events = Sets.newHashSet();
    EventBus bus = new EventBus("watchman test");
    bus.register(
        new Object() {
          @Subscribe
          public void listen(WatchEvent<?> event) {
            events.add(event);
          }
        });
    WatchmanWatcher watcher = createWatcher(
        bus,
        watchmanOutput);
    watcher.postEvents(
        BuckEventBusFactory.newInstance(new FakeClock(0)),
        WatchmanWatcher.FreshInstanceAction.POST_OVERFLOW_EVENT);

    boolean overflowSeen = false;
    for (WatchEvent<?> event : events) {
      overflowSeen |= event.kind().equals(StandardWatchEventKinds.OVERFLOW);
    }
    assertTrue(overflowSeen);
  }

  @Test
  public void whenWatchmanInstanceIsFreshAndActionIsNoneThenCachesNotCleared()
      throws IOException, InterruptedException {
    ImmutableMap<String, Object> watchmanOutput = ImmutableMap.of(
        "version", "2.9.2",
        "clock", "c:1386170113:26390:5:50273",
        "is_fresh_instance", true,
        "files", ImmutableList.of());

    final Set<WatchEvent<?>> events = Sets.newHashSet();
    EventBus bus = new EventBus("watchman test");
    bus.register(
        new Object() {
          @Subscribe
          public void listen(WatchEvent<?> event) {
            events.add(event);
          }
        });
    WatchmanWatcher watcher = createWatcher(
        bus,
        watchmanOutput);
    watcher.postEvents(
        BuckEventBusFactory.newInstance(new FakeClock(0)),
        WatchmanWatcher.FreshInstanceAction.NONE);

    boolean overflowSeen = false;
    for (WatchEvent<?> event : events) {
      overflowSeen |= event.kind().equals(StandardWatchEventKinds.OVERFLOW);
    }
    assertFalse(overflowSeen);
  }

  @Test
  public void whenParseTimesOutThenOverflowGenerated()
      throws IOException, InterruptedException {
    ImmutableMap<String, Object> watchmanOutput = ImmutableMap.of(
        "version", "2.9.2",
        "clock", "c:1386170113:26390:5:50273",
        "is_fresh_instance", true,
        "files", ImmutableList.of());

    final Set<WatchEvent<?>> events = Sets.newHashSet();
    EventBus bus = new EventBus("watchman test");
    bus.register(
        new Object() {
          @Subscribe
          public void listen(WatchEvent<?> event) {
            events.add(event);
          }
        });
    WatchmanWatcher watcher = createWatcher(
        bus,
        new FakeWatchmanClient(
            10000000000L /* queryElapsedTimeNanos */,
            ImmutableMap.of(FAKE_UUID_QUERY, watchmanOutput)),
        -1 /* timeout */);
    watcher.postEvents(
        BuckEventBusFactory.newInstance(new FakeClock(0)),
        WatchmanWatcher.FreshInstanceAction.NONE);

    boolean overflowSeen = false;
    for (WatchEvent<?> event : events) {
      overflowSeen |= event.kind().equals(StandardWatchEventKinds.OVERFLOW);
    }
    assertTrue(overflowSeen);
  }

  @Test
  public void watchmanQueryWithRepoRelativePrefix() {
    WatchmanQuery query = WatchmanWatcher.createQuery(
        ProjectWatch.of("path/to/repo", Optional.of("project")),
        ImmutableSet.of(),
        ImmutableSet.of(Watchman.Capability.DIRNAME));

    assertThat(
        query.toList(""),
        hasItem(hasEntry("relative_root", "project")));
  }

  @Test
  public void watchmanQueryWithExcludePathsAddsExpressionToQuery() {
    WatchmanQuery query = WatchmanWatcher.createQuery(
        ProjectWatch.of("/path/to/repo", Optional.empty()),
        ImmutableSet.of(
            new PathOrGlobMatcher(Paths.get("foo")),
            new PathOrGlobMatcher(Paths.get("bar/baz"))),
        ImmutableSet.of(Watchman.Capability.DIRNAME));
    assertEquals(
        WatchmanQuery.of(
            "/path/to/repo",
            ImmutableMap.of(
                "expression", ImmutableList.of(
                    "not",
                    ImmutableList.of(
                        "anyof",
                        ImmutableList.of("type", "d"),
                        ImmutableList.of("dirname", "foo"),
                        ImmutableList.of(
                            "dirname",
                            MorePaths.pathWithPlatformSeparators("bar/baz")))),
                "empty_on_fresh_instance", true,
                "fields", ImmutableList.of("name", "exists", "new"))),
        query);
  }

  @Test
  public void watchmanQueryWithExcludePathsAddsMatchExpressionToQueryIfDirnameNotAvailable() {
    WatchmanQuery query = WatchmanWatcher.createQuery(
        ProjectWatch.of("/path/to/repo", Optional.empty()),
        ImmutableSet.of(
            new PathOrGlobMatcher(Paths.get("foo")),
            new PathOrGlobMatcher(Paths.get("bar/baz"))),
        ImmutableSet.of());
    assertEquals(
        WatchmanQuery.of(
            "/path/to/repo",
            ImmutableMap.of(
                "expression", ImmutableList.of(
                    "not",
                    ImmutableList.of(
                        "anyof",
                        ImmutableList.of("type", "d"),
                        ImmutableList.of(
                            "match",
                            "foo" + File.separator + "*",
                            "wholename"),
                        ImmutableList.of(
                            "match",
                            "bar" + File.separator + "baz" + File.separator + "*",
                            "wholename"))),
                "empty_on_fresh_instance", true,
                "fields", ImmutableList.of("name", "exists", "new"))),
        query);
  }

  @Test
  public void watchmanQueryRelativizesExcludePaths() {
    String watchRoot = Paths.get("/path/to/repo").toAbsolutePath().toString();
    WatchmanQuery query = WatchmanWatcher.createQuery(
        ProjectWatch.of(watchRoot, Optional.empty()),
        ImmutableSet.of(
            new PathOrGlobMatcher(Paths.get("/path/to/repo/foo").toAbsolutePath()),
            new PathOrGlobMatcher(Paths.get("/path/to/repo/bar/baz").toAbsolutePath())),
        ImmutableSet.of(Watchman.Capability.DIRNAME));
    assertEquals(
        WatchmanQuery.of(
            watchRoot,
            ImmutableMap.of(
                "expression", ImmutableList.of(
                    "not",
                    ImmutableList.of(
                        "anyof",
                        ImmutableList.of("type", "d"),
                        ImmutableList.of("dirname", "foo"),
                        ImmutableList.of(
                            "dirname",
                            MorePaths.pathWithPlatformSeparators("bar/baz")))),
                "empty_on_fresh_instance", true,
                "fields", ImmutableList.of("name", "exists", "new"))),
        query);
  }

  @Test
  public void watchmanQueryWithExcludeGlobsAddsExpressionToQuery() {
    WatchmanQuery query = WatchmanWatcher.createQuery(
        ProjectWatch.of("/path/to/repo", Optional.empty()),
        ImmutableSet.of(
            new PathOrGlobMatcher("*.pbxproj")),
        ImmutableSet.of(Watchman.Capability.DIRNAME));
    assertEquals(
        WatchmanQuery.of(
            "/path/to/repo",
            ImmutableMap.of(
                "expression", ImmutableList.of(
                    "not",
                    ImmutableList.of(
                        "anyof",
                        ImmutableList.of("type", "d"),
                        ImmutableList.of(
                            "match",
                            "*.pbxproj",
                            "wholename",
                            ImmutableMap.<String, Object>of("includedotfiles", true)))),
                "empty_on_fresh_instance", true,
                "fields", ImmutableList.of("name", "exists", "new"))),
        query);
  }

  @Test
  public void whenWatchmanProducesAWarningThenOverflowEventNotGenerated()
      throws IOException, InterruptedException {
    ImmutableMap<String, Object> watchmanOutput = ImmutableMap.of(
        "files", ImmutableList.of(),
        "warning", "message");
    EventBus eventBus = createStrictMock(EventBus.class);
    replay(eventBus);
    WatchmanWatcher watcher = createWatcher(
        eventBus,
        watchmanOutput);
    watcher.postEvents(
        BuckEventBusFactory.newInstance(new FakeClock(0)),
        WatchmanWatcher.FreshInstanceAction.NONE);
    verify(eventBus);
  }

  @Test
  public void whenWatchmanProducesAWarningThenDiagnosticEventGenerated()
      throws IOException, InterruptedException {
    String message = "Find me!";
    ImmutableMap<String, Object> watchmanOutput = ImmutableMap.of(
        "files", ImmutableList.of(),
        "warning", message);
    Capture<WatchmanDiagnosticEvent> eventCapture = newCapture();
    EventBus eventBus = new EventBus("watchman test");
    BuckEventBus buckEventBus = createStrictMock(BuckEventBus.class);
    buckEventBus.post(capture(eventCapture));
    replay(buckEventBus);
    WatchmanWatcher watcher = createWatcher(
        eventBus,
        watchmanOutput);
    watcher.postEvents(
        buckEventBus,
        WatchmanWatcher.FreshInstanceAction.NONE);
    verify(buckEventBus);
    assertThat(
        eventCapture.getValue().getDiagnostic().getMessage(),
        Matchers.containsString(message));
  }

  @Test
  public void whenWatchmanProducesAWarningThenWarningAddedToCache()
      throws IOException, InterruptedException {
    String message = "I'm a warning!";
    ImmutableMap<String, Object> watchmanOutput = ImmutableMap.of(
        "files", ImmutableList.of(),
        "warning", message);
    EventBus eventBus = new EventBus("watchman test");
    WatchmanWatcher watcher = createWatcher(
        eventBus,
        watchmanOutput);
    Set<WatchmanDiagnostic> diagnostics = new HashSet<>();
    BuckEventBus buckEventBus = BuckEventBusFactory.newInstance(new FakeClock(0));
    buckEventBus.register(
        new WatchmanDiagnosticEventListener(
            buckEventBus,
            diagnostics));
    watcher.postEvents(
        buckEventBus,
        WatchmanWatcher.FreshInstanceAction.NONE);
    assertThat(
        diagnostics,
        hasItem(
            WatchmanDiagnostic.of(
                WatchmanDiagnostic.Level.WARNING,
                message)));
  }

  @Test
  public void watcherInsertsAndUpdatesClockId() throws IOException, InterruptedException {
    ImmutableMap<String, Object> watchmanOutput = ImmutableMap.<String, Object>of(
        "clock", "c:0:1",
        "files", ImmutableList.of());
    EventBus eventBus = new EventBus("watchman test");
    WatchmanWatcher watcher = createWatcher(
        eventBus,
        new FakeWatchmanClient(
            0 /* queryElapsedTimeNanos */,
            ImmutableMap.of(FAKE_CLOCK_QUERY, watchmanOutput)),
        10000 /* timeout */,
        "c:0:0" /* sinceParam */);
    assertThat(
      watcher.getWatchmanQuery(FAKE_ROOT),
      hasItem(hasEntry("since", "c:0:0")));

    watcher.postEvents(
        BuckEventBusFactory.newInstance(new FakeClock(0)),
        WatchmanWatcher.FreshInstanceAction.POST_OVERFLOW_EVENT);

    assertThat(
      watcher.getWatchmanQuery(FAKE_ROOT),
      hasItem(hasEntry("since", "c:0:1")));
  }

  @Test
  public void watcherOverflowUpdatesClockId() throws IOException, InterruptedException {
    ImmutableMap<String, Object> watchmanOutput = ImmutableMap.<String, Object>of(
        "clock", "c:1:0",
        "is_fresh_instance", true);
    final Set<WatchEvent<?>> events = Sets.newHashSet();
    EventBus eventBus = new EventBus("watchman test");
    eventBus.register(
        new Object() {
          @Subscribe
          public void listen(WatchEvent<?> event) {
            events.add(event);
          }
        });
    WatchmanWatcher watcher = createWatcher(
        eventBus,
        new FakeWatchmanClient(
            0 /* queryElapsedTimeNanos */,
            ImmutableMap.of(FAKE_CLOCK_QUERY, watchmanOutput)),
        10000 /* timeout */,
        "c:0:0" /* sinceParam */);
    assertThat(
      watcher.getWatchmanQuery(FAKE_ROOT),
      hasItem(hasEntry("since", "c:0:0")));

    watcher.postEvents(
        BuckEventBusFactory.newInstance(new FakeClock(0)),
        WatchmanWatcher.FreshInstanceAction.POST_OVERFLOW_EVENT);

    assertThat(
      watcher.getWatchmanQuery(FAKE_ROOT),
      hasItem(hasEntry("since", "c:1:0")));

    boolean overflowSeen = false;
    for (WatchEvent<?> event : events) {
      overflowSeen |= event.kind().equals(StandardWatchEventKinds.OVERFLOW);
    }
    assertTrue(overflowSeen);
  }

  @Test
  public void whenWatchmanReportsZeroFilesChangedThenPostEvent()
      throws IOException, InterruptedException {
    ImmutableMap<String, Object> watchmanOutput = ImmutableMap.of(
        "files", ImmutableList.of());

    WatchmanWatcher watcher = createWatcher(
        new EventBus("watchman test"),
        watchmanOutput);
    final Set<BuckEvent> events = Sets.newHashSet();
    BuckEventBus bus = BuckEventBusFactory.newInstance(new FakeClock(0));
    bus.register(
        new Object() {
          @Subscribe
          public void listen(WatchmanStatusEvent event) {
            events.add(event);
          }
        });
    watcher.postEvents(
        bus,
        WatchmanWatcher.FreshInstanceAction.POST_OVERFLOW_EVENT);

    boolean zeroFilesChangedSeen = false;
    System.err.println(String.format("Events: %d", events.size()));
    for (BuckEvent event : events) {
      zeroFilesChangedSeen |= event.getEventName().equals("WatchmanZeroFileChanges");
    }
    assertTrue(zeroFilesChangedSeen);
  }

  @Test
  public void whenWatchmanCellReportsFilesChangedThenPostEvent()
      throws IOException, InterruptedException {
    ImmutableMap<String, Object> watchmanRootOutput = ImmutableMap.of(
        "files", ImmutableList.of());
    ImmutableMap<String, Object> watchmanSecondaryOutput = ImmutableMap.of(
        "files", ImmutableList.of(ImmutableMap.<String, Object>of("name", "foo/bar/baz")));

    WatchmanWatcher watcher = new WatchmanWatcher(
        new EventBus("watchman test"),
        new FakeWatchmanClient(
            0,
            ImmutableMap.of(
                FAKE_CLOCK_QUERY, watchmanRootOutput,
                FAKE_SECONDARY_QUERY.toList("c:0:0"), watchmanSecondaryOutput)),
        10000,
        ImmutableMap.of(
            FAKE_ROOT, FAKE_QUERY,
            FAKE_SECONDARY_ROOT, FAKE_SECONDARY_QUERY),
        ImmutableMap.of(
            FAKE_ROOT, new WatchmanCursor("c:0:0"),
            FAKE_SECONDARY_ROOT, new WatchmanCursor("c:0:0")));
    final Set<BuckEvent> events = Sets.newHashSet();
    BuckEventBus bus = BuckEventBusFactory.newInstance(new FakeClock(0));
    bus.register(
        new Object() {
          @Subscribe
          public void listen(WatchmanStatusEvent event) {
            events.add(event);
          }
        });
    watcher.postEvents(
        bus,
        WatchmanWatcher.FreshInstanceAction.POST_OVERFLOW_EVENT);

    boolean zeroFilesChangedSeen = false;
    System.err.println(String.format("Events: %d", events.size()));
    for (BuckEvent event : events) {
      System.err.println(String.format("Event: %s", event));
      zeroFilesChangedSeen |= event.getEventName().equals("WatchmanZeroFileChanges");
    }
    assertFalse(zeroFilesChangedSeen);
  }

  private WatchmanWatcher createWatcher(
      EventBus eventBus,
      ImmutableMap<String, ? extends Object> response) {
    return createWatcher(
        eventBus,
        new FakeWatchmanClient(
            0 /* queryElapsedTimeNanos */,
            ImmutableMap.of(FAKE_UUID_QUERY, response)),
        10000 /* timeout */);
  }

  private WatchmanWatcher createWatcher(EventBus eventBus,
                                        FakeWatchmanClient watchmanClient,
                                        long timeoutMillis) {
    return createWatcher(
        eventBus,
        watchmanClient,
        timeoutMillis,
        "n:buckduuid" /* sinceCursor */);
  }

  private WatchmanWatcher createWatcher(EventBus eventBus,
                                        FakeWatchmanClient watchmanClient,
                                        long timeoutMillis,
                                        String sinceCursor) {
    return new WatchmanWatcher(
        eventBus,
        watchmanClient,
        timeoutMillis,
        ImmutableMap.of(FAKE_ROOT, FAKE_QUERY),
        ImmutableMap.of(FAKE_ROOT, new WatchmanCursor(sinceCursor)));
  }
}
