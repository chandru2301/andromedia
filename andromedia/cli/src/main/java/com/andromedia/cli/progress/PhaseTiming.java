package com.andromedia.cli.progress;

import java.time.Duration;

public record PhaseTiming(CliPhase phase, Duration duration) {}
