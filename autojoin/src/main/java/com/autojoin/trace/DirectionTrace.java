package com.autojoin.trace;

public class DirectionTrace {
    private final DiscoveryTrace discovery;
    private final LearningTrace learning;
    private final ApplicationTrace application;
    private final FuzzyTrace fuzzy;

    public DirectionTrace(DiscoveryTrace discovery, LearningTrace learning,
                          ApplicationTrace application, FuzzyTrace fuzzy) {
        this.discovery = discovery;
        this.learning = learning;
        this.application = application;
        this.fuzzy = fuzzy;
    }

    public DirectionTrace(DiscoveryTrace discovery, LearningTrace learning,
                          ApplicationTrace application) {
        this(discovery, learning, application, null);
    }

    public static DirectionTrace empty() {
        return new DirectionTrace(new DiscoveryTrace(), new LearningTrace(),
                new ApplicationTrace(), null);
    }

    public DiscoveryTrace getDiscovery() { return discovery; }
    public LearningTrace getLearning() { return learning; }
    public ApplicationTrace getApplication() { return application; }
    public FuzzyTrace getFuzzy() { return fuzzy; }
}