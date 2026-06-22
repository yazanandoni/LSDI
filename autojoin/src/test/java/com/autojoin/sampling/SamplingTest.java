package com.autojoin.sampling;

import com.autojoin.model.Column;
import com.autojoin.model.Row;
import com.autojoin.model.Table;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class SamplingTest {
    private Table presidentVotes() {
        return new Table("votes", List.of(
                new Column("President", List.of("Barack Obama", "George W. Bush", "Bill Clinton", "Georg H. W. Bush", "Ronald Reagan"), false),
                new Column("PopularVote", List.of("52.93%", "47.87%", "43.01%", "53.37%", "50.75%"), false)
        ));
    }

    @Test
    public void testDrawSample() {
        Table t = presidentVotes();
        int expectedSize = 3;
        Table sample = Sample.drawSample(t, expectedSize);

        assertNotNull(sample);
        assertEquals(t.numColumns(), sample.numColumns(), "Number of columns must be equal.");

        // simple print check to see that the rows are not split in the sample table
        for (Row row : sample.getRows()) {
            System.out.print(row);
        }
    }
}
