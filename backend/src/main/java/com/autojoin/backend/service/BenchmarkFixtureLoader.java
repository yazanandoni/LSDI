package com.autojoin.backend.service;

import com.google.gson.Gson;

import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

public class BenchmarkFixtureLoader {
    private static final Gson GSON = new Gson();
    private static final Path FIXTURE_DIR = Paths.get("data/fixtures/web-benchmark");

    public BenchmarkFixture loadFixture(String pairId) throws IOException {
        Path fixturePath = FIXTURE_DIR.resolve(pairId).resolve("fixture.json");
        try (FileReader reader = new FileReader(fixturePath.toFile())) {
            return GSON.fromJson(reader, BenchmarkFixture.class);
        }
    }
}
