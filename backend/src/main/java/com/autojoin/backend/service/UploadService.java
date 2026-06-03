package com.autojoin.backend.service;

import com.autojoin.AutoJoin;
import com.autojoin.JoinResult;
import com.autojoin.model.Table;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.Reader;
import java.util.List;

@Service
public class UploadService {
    private final AutoJoin autoJoin = new AutoJoin();

    public JoinResult joinCsv(Reader sourceCsv, Reader targetCsv, List<String> sourceKeys, List<String> targetKeys)
            throws IOException {
        Table sourceTable = Table.fromCsv("upload-source", sourceCsv, sourceKeys);
        Table targetTable = Table.fromCsv("upload-target", targetCsv, targetKeys);
        return autoJoin.join(sourceTable, targetTable);
    }
}