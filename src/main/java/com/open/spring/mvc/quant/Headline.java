package com.open.spring.mvc.quant;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Simple headline structure used in SentimentSnapshot.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Headline {
    private String time;     // ISO-8601 string
    private String title;    // short headline text
    private double score;    // sentiment score for this headline [-1,1]
}
