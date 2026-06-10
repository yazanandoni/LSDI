package com.autojoin.trace;

public class DirectionTrace {
    private final DiscoveryTrace discovery;
    private final LearningTrace learning;
    private final ApplicationTrace application;

    public DirectionTrace(DiscoveryTrace discovery, LearningTrace learning, ApplicationTrace application) {
        this.discovery = discovery;
        this.learning = learning;
        this.application = application;
    }

    public static DirectionTrace empty() {
        return new DirectionTrace(new DiscoveryTrace(), new LearningTrace(), new ApplicationTrace());
    }

    public DiscoveryTrace getDiscovery() { return discovery; }
    public LearningTrace getLearning() { return learning; }
    public ApplicationTrace getApplication() { return application; }
}