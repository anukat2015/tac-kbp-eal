package com.bbn.kbp.events2014;

import com.bbn.bue.common.TextGroupPublicImmutable;
import com.bbn.bue.common.evaluation.AggregateBinaryFScoresInspector;
import com.bbn.bue.common.evaluation.BinaryFScoreBootstrapStrategy;
import com.bbn.bue.common.evaluation.BootstrapInspector;
import com.bbn.bue.common.evaluation.EquivalenceBasedProvenancedAligner;
import com.bbn.bue.common.evaluation.EvalPair;
import com.bbn.bue.common.evaluation.InspectionNode;
import com.bbn.bue.common.evaluation.InspectorTreeDSL;
import com.bbn.bue.common.evaluation.InspectorTreeNode;
import com.bbn.bue.common.evaluation.ProvenancedAlignment;
import com.bbn.bue.common.parameters.Parameters;
import com.bbn.bue.common.strings.offsets.CharOffset;
import com.bbn.bue.common.symbols.Symbol;
import com.bbn.kbp.TACException;
import com.bbn.kbp.events2014.io.DefaultCorpusQueryLoader;
import com.bbn.kbp.events2014.io.SingleFileQueryAssessmentsLoader;
import com.bbn.kbp.events2014.io.SystemOutputStore2016;

import com.google.common.base.Charsets;
import com.google.common.base.Function;
import com.google.common.base.Functions;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Range;
import com.google.common.collect.RangeSet;
import com.google.common.collect.TreeRangeSet;
import com.google.common.io.Files;
import com.google.common.reflect.TypeToken;

import org.immutables.func.Functional;
import org.immutables.value.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static com.bbn.bue.common.evaluation.InspectorTreeDSL.inspect;
import static com.bbn.kbp.events2014.ResponseFunctions.predicateJustifications;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

public final class CorpusScorer {

  private static final Logger log = LoggerFactory.getLogger(CorpusScorer.class);

  private CorpusScorer() {
    throw new UnsupportedOperationException();
  }

  public static void main(String[] argv) {
    // we wrap the main method in this way to
    // ensure a non-zero return value on failure
    try {
      final Parameters params = Parameters.loadSerifStyle(new File(argv[0]));
      trueMain(params);
    } catch (Exception e) {
      e.printStackTrace();
      System.exit(1);
    }
  }

  static void trueMain(Parameters params) throws IOException {
    final File outputDir = params.getCreatableDirectory("com.bbn.tac.eal.outputDir");
    final File queryFile = params.getExistingFile("com.bbn.tac.eal.queryFile");
    final File queryResponseAssessmentsFile =
        params.getExistingFile("com.bbn.tac.eal.queryAssessmentsFile");
    final CorpusQueryAssessments queryAssessments =
        SingleFileQueryAssessmentsLoader.create().loadFrom(
            Files.asCharSource(queryResponseAssessmentsFile, Charsets.UTF_8));
    final CorpusQuerySet2016 queries = DefaultCorpusQueryLoader.create().loadQueries(
        Files.asCharSource(queryFile, Charsets.UTF_8));

    final ImmutableMap<String, SystemOutputStore2016> systemOutputsByName =
        loadSystemOutputs(params);

    log.info("Scoring output will be written to {}", outputDir);

    for (final SystemOutputStore2016 systemOutputStore : systemOutputsByName.values()) {
      score(queries, queryAssessments, systemOutputStore,
          new File(outputDir, systemOutputStore.systemID().asString()));
      systemOutputStore.close();
    }
  }

  private static final CorpusQueryExecutor2016 queryExecutor =
      DefaultCorpusQueryExecutor.createDefaultFor2016();

  private static void score(final CorpusQuerySet2016 queries,
      final CorpusQueryAssessments queryAssessments,
      final SystemOutputStore2016 systemOutputStore,
      File outputDir) throws IOException {
    final TypeToken<Set<QueryDocMatch>> setOfQueryMatches = new TypeToken<Set<QueryDocMatch>>() {
    };
    final InspectionNode<EvalPair<Set<QueryDocMatch>, Set<QueryDocMatch>>> input =
        InspectorTreeDSL.pairedInput(setOfQueryMatches, setOfQueryMatches);
    setUpScoring(input, outputDir);

    final CorrectMatchesFromAssessmentsExtractor matchesFromAssessmentsExtractor =
        new CorrectMatchesFromAssessmentsExtractor();
    final QueryResponsesFromSystemOutputExtractor matchesFromSystemOutputExtractor =
        QueryResponsesFromSystemOutputExtractor.of(queryAssessments, queryExecutor);

    for (final CorpusQuery2016 query : queries) {
      final Set<QueryDocMatch> correctMatches = matchesFromAssessmentsExtractor
          .extractCorrectMatches(query, queryAssessments);
      final Set<QueryDocMatch> systemMatches =
          matchesFromSystemOutputExtractor.extractMatches(query, systemOutputStore);
      input.inspect(EvalPair.of(correctMatches, systemMatches));
    }

    // trigger scoring network to do final aggregated output
    input.finish();
  }

  private static Function<EvalPair<? extends Iterable<? extends QueryDocMatch>, ? extends Iterable<? extends QueryDocMatch>>, ProvenancedAlignment<QueryDocMatch, QueryDocMatch, QueryDocMatch, QueryDocMatch>>
      EXACT_MATCH_ALIGNER = EquivalenceBasedProvenancedAligner
      .forEquivalenceFunction(Functions.<QueryDocMatch>identity())
      .asFunction();

  private static void setUpScoring(
      final InspectionNode<EvalPair<Set<QueryDocMatch>, Set<QueryDocMatch>>> input,
      final File outputDir) {
    final InspectorTreeNode<ProvenancedAlignment<QueryDocMatch, QueryDocMatch, QueryDocMatch, QueryDocMatch>>
        alignment = InspectorTreeDSL.transformed(input, EXACT_MATCH_ALIGNER);

    inspect(alignment)
        .with(AggregateBinaryFScoresInspector.createOutputtingTo("aggregate", outputDir));

    inspect(alignment)
        .with(BootstrapInspector.forStrategy(
            BinaryFScoreBootstrapStrategy.create("Aggregate", outputDir),
            1000, new Random(0)));
  }

  private static final String MULTIPLE_SYSTEMS_PARAM = "com.bbn.tac.eal.systemOutputsDir";
  private static final String SINGLE_SYSTEMS_PARAM = "com.bbn.tac.eal.systemOutputDir";

  /**
   * We can score one or many systems at a time, depending on the parameters
   */
  private static ImmutableMap<String, SystemOutputStore2016> loadSystemOutputs(
      final Parameters params) throws IOException {
    params.assertExactlyOneDefined(SINGLE_SYSTEMS_PARAM, MULTIPLE_SYSTEMS_PARAM);
    final ImmutableMap.Builder<String, SystemOutputStore2016> ret = ImmutableMap.builder();
    if (params.isPresent(MULTIPLE_SYSTEMS_PARAM)) {
      final File systemOutputsDir = params.getExistingDirectory(MULTIPLE_SYSTEMS_PARAM);
      for (final File f : systemOutputsDir.listFiles()) {
        if (f.isDirectory()) {
          ret.put(f.getName(), KBPEA2016OutputLayout.get().open(f));
        }
      }
    } else if (params.isPresent(SINGLE_SYSTEMS_PARAM)) {
      final File singleSystemOutputDir = params.getExistingDirectory(SINGLE_SYSTEMS_PARAM);
      ret.put(singleSystemOutputDir.getName(),
          KBPEA2016OutputLayout.get().open(singleSystemOutputDir));
    } else {
      throw new RuntimeException("Can't happen");
    }
    return ret.build();
  }

}


/**
 * The match of a query against a document.
 */
@Value.Immutable
@Functional
@TextGroupPublicImmutable
abstract class _QueryDocMatch {

  @Value.Parameter
  public abstract Symbol queryID();

  @Value.Parameter
  public abstract Symbol docID();

  @Value.Parameter
  public abstract QueryAssessment2016 assessment();
}

/**
 * Gets all matches of queries against documents by any system where some system's match was
 * assessed as correct.
 */
final class CorrectMatchesFromAssessmentsExtractor {

  public final Set<QueryDocMatch> extractCorrectMatches(final CorpusQuery2016 query,
      final CorpusQueryAssessments input) {
    checkNotNull(input);
    final ImmutableSet.Builder<QueryDocMatch> ret = ImmutableSet.builder();
    for (final Map.Entry<QueryResponse2016, QueryAssessment2016> e
        : input.assessments().entrySet()) {
      final QueryResponse2016 queryResponse = e.getKey();
      final QueryAssessment2016 assessment = e.getValue();

      if (query.id().equalTo(queryResponse.queryID())) {
        checkArgument(!assessment.equals(QueryAssessment2016.UNASSASSED),
            "Response %s for query ID {} is not assessed", queryResponse,
            queryResponse.queryID());
        if (assessment.equals(QueryAssessment2016.CORRECT)) {
          ret.add(QueryDocMatch.of(queryResponse.queryID(), queryResponse.docID(), assessment));
        }
      }
    }
    return ret.build();
  }
}

@TextGroupPublicImmutable
@Value.Immutable
abstract class _QueryResponsesFromSystemOutputExtractor {

  @Value.Parameter
  public abstract CorpusQueryAssessments corpusQueryAssessments();

  @Value.Parameter
  public abstract CorpusQueryExecutor2016 queryExecutor();

  public final Set<QueryDocMatch> extractMatches(final CorpusQuery2016 query,
      final SystemOutputStore2016 input) {
    checkNotNull(input);
    final ImmutableSet.Builder<QueryDocMatch> ret = ImmutableSet.builder();
    try {
      for (final DocEventFrameReference match : queryExecutor().queryEventFrames(input, query)) {
        final ResponseLinking linkingForMatchedDocument = input.read(match.docID()).linking();
        if (linkingForMatchedDocument.responseSetIds().isPresent()) {
          final QueryResponse2016 queryResponse =
              QueryResponse2016.of(query.id(), match.docID(),
                  mergePJs(linkingForMatchedDocument.responseSetIds().get()
                      .get(match.eventFrameID())));
          final QueryAssessment2016 assessment =
              corpusQueryAssessments().assessments().get(queryResponse);
          if (assessment != null) {
            ret.add(QueryDocMatch.of(query.id(), match.docID(), assessment));
          } else {
            throw new TACException("Query response " + queryResponse + " is unassessed");
          }
        } else {
          throw new TACException("Linking for document " + match.docID() + " lacks IDs");
        }
      }
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
    return ret.build();
  }

  /**
   * Coalesce the predicate justifications of all responses, combining overlapping spans.
   */
  private final Set<CharOffsetSpan> mergePJs(final ResponseSet responses) {
    final RangeSet<CharOffset> pjRanges = TreeRangeSet.create();

    final FluentIterable<CharOffsetSpan> pjsOfAnyLinkedResponses = FluentIterable.from(responses)
        .transformAndConcat(predicateJustifications());

    for (final CharOffsetSpan pjSpan : pjsOfAnyLinkedResponses) {
      pjRanges.add(pjSpan.asCharOffsetRange().asRange());
    }

    final ImmutableSet.Builder<CharOffsetSpan> ret = ImmutableSet.builder();
    for (final Range<CharOffset> mergedPJ : pjRanges.asRanges()) {
      ret.add(CharOffsetSpan.fromOffsetsOnly(mergedPJ.lowerEndpoint().asInt(),
          mergedPJ.upperEndpoint().asInt()));
    }
    return ret.build();
  }
}

