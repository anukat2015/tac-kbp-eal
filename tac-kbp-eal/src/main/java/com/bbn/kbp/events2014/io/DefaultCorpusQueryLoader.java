package com.bbn.kbp.events2014.io;

import com.bbn.bue.common.StringUtils;
import com.bbn.bue.common.strings.offsets.CharOffset;
import com.bbn.bue.common.strings.offsets.OffsetRange;
import com.bbn.bue.common.symbols.Symbol;
import com.bbn.kbp.events2014.CorpusQuery2016;
import com.bbn.kbp.events2014.CorpusQueryEntryPoint;
import com.bbn.kbp.events2014.CorpusQueryLoader;
import com.bbn.kbp.events2014.CorpusQuerySet2016;
import com.bbn.kbp.events2014.TACKBPEALException;

import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.io.CharSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import static com.bbn.bue.common.symbols.SymbolUtils.byStringOrdering;

public final class DefaultCorpusQueryLoader implements CorpusQueryLoader {

  private static final Logger log = LoggerFactory.getLogger(DefaultCorpusQueryLoader.class);

  private DefaultCorpusQueryLoader() {
  }

  public static DefaultCorpusQueryLoader create() {
    return new DefaultCorpusQueryLoader();
  }

  @Override
  public CorpusQuerySet2016 loadQueries(final CharSource source) throws IOException {
    final ImmutableMultimap.Builder<Symbol, CorpusQueryEntryPoint> queriesToEntryPoints =
        ImmutableMultimap.<Symbol, CorpusQueryEntryPoint>builder().orderKeysBy(byStringOrdering());

    int numEntryPoints = 0;
    int lineNo = 1;
    for (final String line : source.readLines()) {
      try {
        final List<String> parts = StringUtils.onTabs().splitToList(line);
        if (parts.size() != 6) {
          throw new TACKBPEALException("Expected 6 tab-separated fields but got " + parts.size());
        }

        final Symbol queryID = Symbol.from(parts.get(0));
        final Symbol docID = Symbol.from(parts.get(1));
        final Symbol eventType = Symbol.from(parts.get(2));
        final Symbol role = Symbol.from(parts.get(3));
        final OffsetRange<CharOffset> casOffsets =
            TACKBPEALIOUtils.parseCharOffsetRange(parts.get(4));
        final OffsetRange<CharOffset> pjOffsets =
            TACKBPEALIOUtils.parseCharOffsetRange(parts.get(5));

        queriesToEntryPoints.put(queryID,
            CorpusQueryEntryPoint.of(docID, eventType, role, casOffsets, pjOffsets));

        ++lineNo;
        ++numEntryPoints;
      } catch (Exception e) {
        throw new IOException("Invalid query line " + lineNo + " in " + source + ": " + line, e);
      }
    }

    final ImmutableSet.Builder<CorpusQuery2016> ret = ImmutableSet.builder();
    for (final Map.Entry<Symbol, Collection<CorpusQueryEntryPoint>> e
        : queriesToEntryPoints.build().asMap().entrySet()) {
      ret.add(CorpusQuery2016.of(e.getKey(), e.getValue()));
    }

    final CorpusQuerySet2016 corpusQuerySet = CorpusQuerySet2016.of(ret.build());
    log.info("Loaded {} queries with {} entry points from {}", corpusQuerySet.queries().size(),
        numEntryPoints, source);
    return corpusQuerySet;
  }
}

