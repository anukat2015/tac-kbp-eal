package com.bbn.kbp.events;

import com.bbn.bue.common.HasDocID;
import com.bbn.bue.common.TextGroupPublicImmutable;
import com.bbn.bue.common.symbols.Symbol;
import com.bbn.nlp.events.HasEventArgType;
import com.bbn.nlp.events.HasEventType;

import com.google.common.collect.ImmutableSet;

import org.immutables.func.Functional;
import org.immutables.value.Value;

import java.util.Iterator;

@Value.Immutable
@Functional
@TextGroupPublicImmutable
public abstract class _DocLevelEventArg implements HasDocID, HasEventType, HasEventArgType {

  public abstract Symbol docID();

  public abstract Symbol eventArgumentType();

  public abstract Symbol eventType();

  public abstract Symbol realis();

  public abstract String corefID();

}

/*public abstract class _DocLevelEventArgLinking implements HasDocID {
  public abstract Symbol docID();
  public abstract Immutable
}*/
