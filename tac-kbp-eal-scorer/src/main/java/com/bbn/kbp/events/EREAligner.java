package com.bbn.kbp.events;

import com.bbn.bue.common.collections.ImmutableOverlappingRangeSet;
import com.bbn.bue.common.strings.offsets.CharOffset;
import com.bbn.bue.common.strings.offsets.OffsetRange;
import com.bbn.kbp.events2014.CharOffsetSpan;
import com.bbn.kbp.events2014.Response;
import com.bbn.nlp.corenlp.CoreNLPDocument;
import com.bbn.nlp.corenlp.CoreNLPParseNode;
import com.bbn.nlp.corenlp.CoreNLPSentence;
import com.bbn.nlp.corpora.ere.EREArgument;
import com.bbn.nlp.corpora.ere.EREDocument;
import com.bbn.nlp.corpora.ere.EREEntity;
import com.bbn.nlp.corpora.ere.EREEntityMention;
import com.bbn.nlp.corpora.ere.EREEvent;
import com.bbn.nlp.corpora.ere.EREEventMention;
import com.bbn.nlp.corpora.ere.EREFiller;
import com.bbn.nlp.corpora.ere.EREFillerArgument;
import com.bbn.nlp.corpora.ere.ERESpan;

import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Range;

import javax.annotation.Nullable;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

/**
 * Aligns a TAC KBP {@link Response} to appropriate ERE types by offset matching. Offset matching
 * may be relaxed in two ways: <ul> <li>By fuzzier offset matching (e.g. allowing containment or
 * overlap)</li> <li>By finding the head of the KBP {@link Response} and aligning with the ERE head
 * (if any)</li> </ul>
 */
final class EREAligner {

  private final boolean relaxUsingCORENLP;
  private final boolean useExactMatchForCoreNLPRelaxation;
  private final EREDocument ereDoc;
  private final ImmutableMultimap<Range<CharOffset>, EREEntityMention> exactRangeToEntityMention;
  private final ImmutableMultimap<Range<CharOffset>, EREEntityMention>
      exactRangeToEntityMentionHead;
  private final ImmutableOverlappingRangeSet<CharOffset> exactHeadRange;
  private final ImmutableMultimap<Range<CharOffset>, EREFiller> rangeToFiller;
  private final Optional<CoreNLPDocument> coreNLPDoc;

  private EREAligner(final boolean relaxUsingCORENLP,
      final boolean useExactMatchForCoreNLPRelaxation,
      final EREDocument ereDoc, final Optional<CoreNLPDocument> coreNLPDocument) {
    this.relaxUsingCORENLP = relaxUsingCORENLP;
    this.useExactMatchForCoreNLPRelaxation = useExactMatchForCoreNLPRelaxation;
    this.ereDoc = ereDoc;
    this.exactRangeToEntityMention =
        buildRangeToFillerMap(ereDoc, spanExtractor);
    this.exactRangeToEntityMentionHead =
        buildRangeToFillerMap(ereDoc, headExtractor);
    this.exactHeadRange =
        ImmutableOverlappingRangeSet.create(exactRangeToEntityMentionHead.keySet());
    this.coreNLPDoc = coreNLPDocument;
    this.rangeToFiller = buildRangeToFillerMap(ereDoc);
    checkState(!relaxUsingCORENLP || coreNLPDoc.isPresent(),
        "Either we have our CoreNLPDocument or we are not relaxing using it");
  }

  static EREAligner create(final boolean relaxUsingCORENLP,
      final boolean useExactMatchForCoreNLPRelaxation,
      final EREDocument ereDoc, final Optional<CoreNLPDocument> coreNLPDocument) {
    return new EREAligner(relaxUsingCORENLP, useExactMatchForCoreNLPRelaxation, ereDoc,
        coreNLPDocument);
  }


  ImmutableSet<EREEntity> entitiesForResponse(final Response response) {
    return getCandidateEntitiesFromMentions(ereDoc, mentionsForResponse(response));
  }

  ImmutableSet<EREEntityMention> mentionsForResponse(final Response response) {
    // we try to align a system response to an ERE entity by exact offset match of the
    // basefiller against one of an entity's mentions
    // this search could be faster but is probably good enough

    // collect all the candidate mentions
    final ImmutableSet.Builder<EREEntityMention> candidateMentionsB = ImmutableSet.builder();
    final OffsetRange<CharOffset> baseFillerOffsets = response.baseFiller().asCharOffsetRange();
    if (relaxUsingCORENLP) {
      for (final EREEntity e : ereDoc.getEntities()) {
        for (final EREEntityMention em : e.getMentions()) {
          final ERESpan es = em.getExtent();
          final Optional<ERESpan> ereHead = em.getHead();
          if (spanMatches(es, ereHead, CharOffsetSpan.of(baseFillerOffsets))) {
            candidateMentionsB.add(em);
          }
        }
      }
    }

    return candidateMentionsB.build();
  }

  ImmutableSet<EREFillerArgument> fillersForResponse(final Response response) {
    final ImmutableSet.Builder<EREFillerArgument> ret = ImmutableSet.builder();
    for (final EREEvent e : ereDoc.getEvents()) {
      for (final EREEventMention em : e.getEventMentions()) {
        for (final EREArgument ea : em.getArguments()) {
          if (ea instanceof EREFillerArgument) {
            final ERESpan extent = ((EREFillerArgument) ea).filler().getExtent();
            if (spanMatches(extent, Optional.<ERESpan>absent(), response.baseFiller())) {
              ret.add((EREFillerArgument) ea);
            }
          }
        }
      }
    }
    return ret.build();
  }

  private Optional<Range<CharOffset>> getCoreNLPHead(final OffsetRange<CharOffset> offsets) {
    if (!coreNLPDoc.isPresent()) {
      return Optional.absent();
    }
    final Optional<CoreNLPSentence> sent =
        coreNLPDoc.get().firstSentenceContaining(offsets);
    if (sent.isPresent()) {
      final Optional<CoreNLPParseNode> node =
          sent.get().nodeForOffsets(offsets);
      if (node.isPresent()) {
        final Optional<CoreNLPParseNode> terminalHead = node.get().terminalHead();
        if (terminalHead.isPresent()) {
          final Range<CharOffset> coreNLPHeadRange = terminalHead.get().span().asRange();
          return Optional.of(coreNLPHeadRange);
        }
      }
    }
    return Optional.absent();
  }

  private static ImmutableSet<EREEntity> getCandidateEntitiesFromMentions(final EREDocument doc,
      final ImmutableSet<EREEntityMention> candidateMentions) {
    final ImmutableSet.Builder<EREEntity> ret = ImmutableSet.builder();
    for (final EREEntityMention em : candidateMentions) {
      final Optional<EREEntity> e = doc.getEntityContaining(em);
      if (e.isPresent()) {
        ret.add(e.get());
      }
    }
    return ret.build();
  }

  private static ImmutableMultimap<Range<CharOffset>, EREEntityMention> buildRangeToFillerMap(
      final EREDocument ereDocument,
      Function<EREEntityMention, Optional<Range<CharOffset>>> extractor) {
    final ImmutableMultimap.Builder<Range<CharOffset>, EREEntityMention> ret =
        ImmutableMultimap.builder();
    for (final EREEntity e : ereDocument.getEntities()) {
      for (final EREEntityMention em : e.getMentions()) {
        final Optional<Range<CharOffset>> range = checkNotNull(extractor.apply(em));
        if (range.isPresent()) {
          ret.put(range.get(), em);
        }
      }
    }
    return ret.build();
  }

  private static ImmutableMultimap<Range<CharOffset>, EREFiller> buildRangeToFillerMap(
      final EREDocument ereDoc) {
    final ImmutableMultimap.Builder<Range<CharOffset>, EREFiller> ret =
        ImmutableMultimap.builder();
    for (final EREFiller f : ereDoc.getFillers()) {
      final Range<CharOffset> r =
          CharOffsetSpan.fromOffsetsOnly(f.getExtent().getStart(), f.getExtent().getEnd())
              .asCharOffsetRange().asRange();
      ret.put(r, f);
    }
    return ret.build();
  }

  private static final Function<EREEntityMention, Optional<Range<CharOffset>>> spanExtractor =
      new Function<EREEntityMention, Optional<Range<CharOffset>>>() {
        @Nullable
        @Override
        public Optional<Range<CharOffset>> apply(
            @Nullable final EREEntityMention ereEntityMention) {
          checkNotNull(ereEntityMention);
          return Optional
              .of(CharOffsetSpan.fromOffsetsOnly(ereEntityMention.getExtent().getStart(),
                  ereEntityMention.getExtent().getEnd()).asCharOffsetRange().asRange());
        }
      };

  private static final Function<EREEntityMention, Optional<Range<CharOffset>>> headExtractor =
      new Function<EREEntityMention, Optional<Range<CharOffset>>>() {
        @Nullable
        @Override
        public Optional<Range<CharOffset>> apply(
            @Nullable final EREEntityMention ereEntityMention) {
          checkNotNull(ereEntityMention);
          if (ereEntityMention.getHead().isPresent()) {
            return Optional.of(CharOffsetSpan
                .fromOffsetsOnly(ereEntityMention.getHead().get().getStart(),
                    ereEntityMention.getHead().get().getEnd()).asCharOffsetRange().asRange());
          } else {
            return Optional.absent();
          }
        }
      };

  private static Range<CharOffset> rangeFromERESpan(final ERESpan es) {
    return CharOffsetSpan.fromOffsetsOnly(es.getStart(), es.getEnd()).asCharOffsetRange().asRange();
  }

  private boolean spanMatches(final ERESpan es, final Optional<ERESpan> ereHead,
      final CharOffsetSpan cs) {
    final Range<CharOffset> esRange = rangeFromERESpan(es);
    final Optional<Range<CharOffset>> ereHeadRange = ereHead.isPresent() ? Optional
        .of(rangeFromERESpan(ereHead.get())) : Optional.<Range<CharOffset>>absent();
    final Range<CharOffset> csRange = cs.asCharOffsetRange().asRange();
    if (esRange.equals(csRange) || (ereHeadRange.isPresent() && ereHeadRange.get()
        .equals(csRange))) {
      return true;
    }
    // we assume that the ERESpan encloses its head
    if (!useExactMatchForCoreNLPRelaxation && (esRange.encloses(csRange))) {
      return true;
    }
    if (relaxUsingCORENLP) {
      final Optional<Range<CharOffset>> coreNLPHead = getCoreNLPHead(cs.asCharOffsetRange());
      if (coreNLPHead.isPresent()) {
        if (esRange.equals(coreNLPHead.get()) || (ereHeadRange.isPresent() && ereHeadRange.get()
            .equals(coreNLPHead.get()))) {
          return true;
        }
        if (!useExactMatchForCoreNLPRelaxation) {
          // if one contains the other
          if (esRange.encloses(coreNLPHead.get()) || coreNLPHead.get().encloses(esRange)) {
            // if they both contain the head, for either notion of head
            if ((esRange.encloses(coreNLPHead.get()) || (ereHeadRange.isPresent() && esRange
                .encloses(ereHeadRange.get())))
                && (csRange.encloses(coreNLPHead.get()) || (ereHeadRange.isPresent()) && csRange
                .encloses(ereHeadRange.get()))) {
              return true;
            }
          }
        }
      }
    }
    return false;
  }

}
