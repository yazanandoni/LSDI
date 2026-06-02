package com.autojoin.backend.service;

import com.google.gson.Gson;

import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Path;

public class BenchmarkFixtureLoader {
    private static final Gson GSON = new Gson();
    private final Path fixtureDir;

    public BenchmarkFixtureLoader(Path fixtureDir) {
        this.fixtureDir = fixtureDir;
    }

    public BenchmarkFixture loadFixture(String pairId) throws IOException {
        Path fixturePath = fixtureDir.resolve(pairId).resolve("fixture.json");
        try (FileReader reader = new FileReader(fixturePath.toFile())) {
            return GSON.fromJson(reader, BenchmarkFixture.class);
        }
    }
}
