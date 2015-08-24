package com.bbn.kbp.events2014.scorer;

import com.bbn.kbp.events2014.scorer.bin.ImmutableAggregateResult;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;

import org.immutables.value.Value;

import static com.google.common.base.Preconditions.checkArgument;

@Value.Immutable
@JsonSerialize(as = ImmutableAggregateResult.class)
@JsonDeserialize(as = ImmutableAggregateResult.class)
public abstract class Aggregate2015ArgScoringResult {

  abstract double precision();

  abstract double recall();

  abstract double overall();

  abstract double truePositives();

  abstract double falsePositives();

  abstract double falseNegatives();

  protected void check() {
    checkArgument(precision() >= 0.0);
    checkArgument(recall() >= 0.0);
    checkArgument(truePositives() >= 0.0);
    checkArgument(falsePositives() >= 0.0);
    checkArgument(falseNegatives() >= 0.0);
  }
}

