/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.elasticsearch.messy.tests;

import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.common.logging.Loggers;
import org.elasticsearch.common.util.CollectionUtils;
import org.elasticsearch.plugins.Plugin;
import org.elasticsearch.script.Script;
import org.elasticsearch.script.ScriptService.ScriptType;
import org.elasticsearch.script.groovy.GroovyPlugin;
import org.elasticsearch.search.aggregations.bucket.global.Global;
import org.elasticsearch.search.aggregations.bucket.histogram.Histogram;
import org.elasticsearch.search.aggregations.bucket.histogram.Histogram.Order;
import org.elasticsearch.search.aggregations.metrics.AbstractNumericTestCase;
import org.elasticsearch.search.aggregations.metrics.percentiles.Percentile;
import org.elasticsearch.search.aggregations.metrics.percentiles.PercentileRanks;
import org.elasticsearch.search.aggregations.metrics.percentiles.PercentileRanksBuilder;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.elasticsearch.index.query.QueryBuilders.matchAllQuery;
import static org.elasticsearch.search.aggregations.AggregationBuilders.*;
import static org.elasticsearch.test.hamcrest.ElasticsearchAssertions.assertHitCount;
import static org.hamcrest.Matchers.*;

/**
 *
 */
public class TDigestPercentileRanksTests extends AbstractNumericTestCase {

    @Override
    protected Collection<Class<? extends Plugin>> nodePlugins() {
        return pluginList(GroovyPlugin.class);
    }
    
    private static double[] randomPercents(long minValue, long maxValue) {

        final int length = randomIntBetween(1, 20);
        final double[] percents = new double[length];
        for (int i = 0; i < percents.length; ++i) {
            switch (randomInt(20)) {
            case 0:
                percents[i] = minValue;
                break;
            case 1:
                percents[i] = maxValue;
                break;
            default:
                percents[i] = (randomDouble() * (maxValue - minValue)) + minValue;
                break;
            }
        }
        Arrays.sort(percents);
        Loggers.getLogger(TDigestPercentileRanksTests.class).info("Using percentiles={}", Arrays.toString(percents));
        return percents;
    }

    private static PercentileRanksBuilder randomCompression(PercentileRanksBuilder builder) {
        if (randomBoolean()) {
            builder.compression(randomIntBetween(20, 120) + randomDouble());
        }
        return builder;
    }

    private void assertConsistent(double[] pcts, PercentileRanks percentiles, long minValue, long maxValue) {
        final List<Percentile> percentileList = CollectionUtils.iterableAsArrayList(percentiles);
        assertEquals(pcts.length, percentileList.size());
        for (int i = 0; i < pcts.length; ++i) {
            final Percentile percentile = percentileList.get(i);
            assertThat(percentile.getValue(), equalTo(pcts[i]));
            assertThat(percentile.getPercent(), greaterThanOrEqualTo(0.0));
            assertThat(percentile.getPercent(), lessThanOrEqualTo(100.0));

            if (percentile.getPercent() == 0) {
                assertThat(percentile.getValue(), lessThanOrEqualTo((double) minValue));
            }
            if (percentile.getPercent() == 100) {
                assertThat(percentile.getValue(), greaterThanOrEqualTo((double) maxValue));
            }
        }

        for (int i = 1; i < percentileList.size(); ++i) {
            assertThat(percentileList.get(i).getValue(), greaterThanOrEqualTo(percentileList.get(i - 1).getValue()));
        }
    }

    @Override
    @Test
    public void testEmptyAggregation() throws Exception {

        SearchResponse searchResponse = client().prepareSearch("empty_bucket_idx")
                .setQuery(matchAllQuery())
                .addAggregation(histogram("histo").field("value").interval(1l).minDocCount(0)
                        .subAggregation(randomCompression(percentileRanks("percentile_ranks"))
                                .percentiles(10, 15)))
                .execute().actionGet();

        assertThat(searchResponse.getHits().getTotalHits(), equalTo(2l));
        Histogram histo = searchResponse.getAggregations().get("histo");
        assertThat(histo, notNullValue());
        Histogram.Bucket bucket = histo.getBuckets().get(1);
        assertThat(bucket, notNullValue());

        PercentileRanks reversePercentiles = bucket.getAggregations().get("percentile_ranks");
        assertThat(reversePercentiles, notNullValue());
        assertThat(reversePercentiles.getName(), equalTo("percentile_ranks"));
        assertThat(reversePercentiles.percent(10), equalTo(Double.NaN));
        assertThat(reversePercentiles.percent(15), equalTo(Double.NaN));
    }

    @Override
    @Test
    public void testUnmapped() throws Exception {
        SearchResponse searchResponse = client().prepareSearch("idx_unmapped")
                .setQuery(matchAllQuery())
                .addAggregation(randomCompression(percentileRanks("percentile_ranks"))
                        .field("value")
                        .percentiles(0, 10, 15, 100))
                .execute().actionGet();

        assertThat(searchResponse.getHits().getTotalHits(), equalTo(0l));

        PercentileRanks reversePercentiles = searchResponse.getAggregations().get("percentile_ranks");
        assertThat(reversePercentiles, notNullValue());
        assertThat(reversePercentiles.getName(), equalTo("percentile_ranks"));
        assertThat(reversePercentiles.percent(0), equalTo(Double.NaN));
        assertThat(reversePercentiles.percent(10), equalTo(Double.NaN));
        assertThat(reversePercentiles.percent(15), equalTo(Double.NaN));
        assertThat(reversePercentiles.percent(100), equalTo(Double.NaN));
    }

    @Override
    @Test
    public void testSingleValuedField() throws Exception {
        final double[] pcts = randomPercents(minValue, maxValue);
        SearchResponse searchResponse = client().prepareSearch("idx")
                .setQuery(matchAllQuery())
                .addAggregation(randomCompression(percentileRanks("percentile_ranks"))
                        .field("value")
                        .percentiles(pcts))
                .execute().actionGet();

        assertHitCount(searchResponse, 10);

        final PercentileRanks percentiles = searchResponse.getAggregations().get("percentile_ranks");
        assertConsistent(pcts, percentiles, minValue, maxValue);
    }

    @Override
    @Test
    public void testSingleValuedField_getProperty() throws Exception {
        final double[] pcts = randomPercents(minValue, maxValue);
        SearchResponse searchResponse = client()
                .prepareSearch("idx")
                .setQuery(matchAllQuery())
                .addAggregation(
                        global("global").subAggregation(
                                randomCompression(percentileRanks("percentile_ranks")).field("value").percentiles(pcts))).execute()
                .actionGet();

        assertHitCount(searchResponse, 10);

        Global global = searchResponse.getAggregations().get("global");
        assertThat(global, notNullValue());
        assertThat(global.getName(), equalTo("global"));
        assertThat(global.getDocCount(), equalTo(10l));
        assertThat(global.getAggregations(), notNullValue());
        assertThat(global.getAggregations().asMap().size(), equalTo(1));

        PercentileRanks percentiles = global.getAggregations().get("percentile_ranks");
        assertThat(percentiles, notNullValue());
        assertThat(percentiles.getName(), equalTo("percentile_ranks"));
        assertThat((PercentileRanks) global.getProperty("percentile_ranks"), sameInstance(percentiles));

    }

    @Test
    public void testSingleValuedFieldOutsideRange() throws Exception {
        final double[] pcts = new double[] {minValue - 1, maxValue + 1};
        SearchResponse searchResponse = client().prepareSearch("idx")
                .setQuery(matchAllQuery())
                .addAggregation(randomCompression(percentileRanks("percentile_ranks"))
                        .field("value")
                        .percentiles(pcts))
                .execute().actionGet();

        assertHitCount(searchResponse, 10);

        final PercentileRanks percentiles = searchResponse.getAggregations().get("percentile_ranks");
        assertConsistent(pcts, percentiles, minValue, maxValue);
    }

    @Override
    @Test
    public void testSingleValuedField_PartiallyUnmapped() throws Exception {
        final double[] pcts = randomPercents(minValue, maxValue);
        SearchResponse searchResponse = client().prepareSearch("idx", "idx_unmapped")
                .setQuery(matchAllQuery())
                .addAggregation(randomCompression(percentileRanks("percentile_ranks"))
                        .field("value")
                        .percentiles(pcts))
                .execute().actionGet();

        assertHitCount(searchResponse, 10);

        final PercentileRanks percentiles = searchResponse.getAggregations().get("percentile_ranks");
        assertConsistent(pcts, percentiles, minValue, maxValue);
    }

    @Override
    @Test
    public void testSingleValuedField_WithValueScript() throws Exception {
        final double[] pcts = randomPercents(minValue - 1, maxValue - 1);
        SearchResponse searchResponse = client().prepareSearch("idx")
                .setQuery(matchAllQuery())
                .addAggregation(randomCompression(percentileRanks("percentile_ranks"))
.field("value").script(new Script("_value - 1"))
                        .percentiles(pcts))
                .execute().actionGet();

        assertHitCount(searchResponse, 10);

        final PercentileRanks percentiles = searchResponse.getAggregations().get("percentile_ranks");
        assertConsistent(pcts, percentiles, minValue - 1, maxValue - 1);
    }

    @Override
    @Test
    public void testSingleValuedField_WithValueScript_WithParams() throws Exception {
        Map<String, Object> params = new HashMap<>();
        params.put("dec", 1);
        final double[] pcts = randomPercents(minValue - 1, maxValue - 1);
        SearchResponse searchResponse = client().prepareSearch("idx")
                .setQuery(matchAllQuery())
                .addAggregation(randomCompression(percentileRanks("percentile_ranks"))
.field("value")
                                .script(new Script("_value - dec", ScriptType.INLINE, null, params))
                        .percentiles(pcts))
                .execute().actionGet();

        assertHitCount(searchResponse, 10);

        final PercentileRanks percentiles = searchResponse.getAggregations().get("percentile_ranks");
        assertConsistent(pcts, percentiles, minValue - 1, maxValue - 1);
    }

    @Override
    @Test
    public void testMultiValuedField() throws Exception {
        final double[] pcts = randomPercents(minValues, maxValues);
        SearchResponse searchResponse = client().prepareSearch("idx")
                .setQuery(matchAllQuery())
                .addAggregation(randomCompression(percentileRanks("percentile_ranks"))
                        .field("values")
                        .percentiles(pcts))
                .execute().actionGet();

        assertHitCount(searchResponse, 10);

        final PercentileRanks percentiles = searchResponse.getAggregations().get("percentile_ranks");
        assertConsistent(pcts, percentiles, minValues, maxValues);
    }

    @Override
    @Test
    public void testMultiValuedField_WithValueScript() throws Exception {
        final double[] pcts = randomPercents(minValues - 1, maxValues - 1);
        SearchResponse searchResponse = client().prepareSearch("idx")
                .setQuery(matchAllQuery())
                .addAggregation(randomCompression(percentileRanks("percentile_ranks"))
.field("values").script(new Script("_value - 1"))
                        .percentiles(pcts))
                .execute().actionGet();

        assertHitCount(searchResponse, 10);

        final PercentileRanks percentiles = searchResponse.getAggregations().get("percentile_ranks");
        assertConsistent(pcts, percentiles, minValues - 1, maxValues - 1);
    }

    @Test
    public void testMultiValuedField_WithValueScript_Reverse() throws Exception {
        final double[] pcts = randomPercents(-maxValues, -minValues);
        SearchResponse searchResponse = client().prepareSearch("idx")
                .setQuery(matchAllQuery())
                .addAggregation(randomCompression(percentileRanks("percentile_ranks"))
.field("values").script(new Script("_value * -1"))
                        .percentiles(pcts))
                .execute().actionGet();

        assertHitCount(searchResponse, 10);

        final PercentileRanks percentiles = searchResponse.getAggregations().get("percentile_ranks");
        assertConsistent(pcts, percentiles, -maxValues, -minValues);
    }

    @Override
    @Test
    public void testMultiValuedField_WithValueScript_WithParams() throws Exception {
        Map<String, Object> params = new HashMap<>();
        params.put("dec", 1);
        final double[] pcts = randomPercents(minValues - 1, maxValues - 1);
        SearchResponse searchResponse = client().prepareSearch("idx")
                .setQuery(matchAllQuery())
                .addAggregation(randomCompression(percentileRanks("percentile_ranks"))
.field("values")
                                .script(new Script("_value - dec", ScriptType.INLINE, null, params))
                        .percentiles(pcts))
                .execute().actionGet();

        assertHitCount(searchResponse, 10);

        final PercentileRanks percentiles = searchResponse.getAggregations().get("percentile_ranks");
        assertConsistent(pcts, percentiles, minValues - 1, maxValues - 1);
    }

    @Override
    @Test
    public void testScript_SingleValued() throws Exception {
        final double[] pcts = randomPercents(minValue, maxValue);
        SearchResponse searchResponse = client().prepareSearch("idx")
                .setQuery(matchAllQuery())
                .addAggregation(randomCompression(percentileRanks("percentile_ranks"))
.script(new Script("doc['value'].value"))
                        .percentiles(pcts))
                .execute().actionGet();

        assertHitCount(searchResponse, 10);

        final PercentileRanks percentiles = searchResponse.getAggregations().get("percentile_ranks");
        assertConsistent(pcts, percentiles, minValue, maxValue);
    }

    @Override
    @Test
    public void testScript_SingleValued_WithParams() throws Exception {
        Map<String, Object> params = new HashMap<>();
        params.put("dec", 1);
        final double[] pcts = randomPercents(minValue - 1, maxValue - 1);
        SearchResponse searchResponse = client().prepareSearch("idx")
                .setQuery(matchAllQuery())
                .addAggregation(randomCompression(percentileRanks("percentile_ranks"))
.script(
                                new Script("doc['value'].value - dec", ScriptType.INLINE, null, params))
                        .percentiles(pcts))
                .execute().actionGet();

        assertHitCount(searchResponse, 10);

        final PercentileRanks percentiles = searchResponse.getAggregations().get("percentile_ranks");
        assertConsistent(pcts, percentiles, minValue - 1, maxValue - 1);
    }

    @Override
    public void testScriptMultiValued() throws Exception {
        final double[] pcts = randomPercents(minValues, maxValues);
        SearchResponse searchResponse = client().prepareSearch("idx")
                .setQuery(matchAllQuery())
                .addAggregation(randomCompression(percentileRanks("percentile_ranks"))
.script(new Script("doc['values'].values"))
                        .percentiles(pcts))
                .execute().actionGet();

        assertHitCount(searchResponse, 10);

        final PercentileRanks percentiles = searchResponse.getAggregations().get("percentile_ranks");
        assertConsistent(pcts, percentiles, minValues, maxValues);
    }

    @Override
    public void testScriptMultiValuedWithParams() throws Exception {
        Map<String, Object> params = new HashMap<>();
        params.put("dec", 1);
        final double[] pcts = randomPercents(minValues - 1, maxValues - 1);
        SearchResponse searchResponse = client().prepareSearch("idx")
                .setQuery(matchAllQuery())
                .addAggregation(randomCompression(percentileRanks("percentile_ranks"))
                                .script(new Script(
                                        "List values = doc['values'].values; double[] res = new double[values.size()]; for (int i = 0; i < res.length; i++) { res[i] = values.get(i) - dec; }; return res;",
                                        ScriptType.INLINE, null, params))
                        .percentiles(pcts))
                .execute().actionGet();

        assertHitCount(searchResponse, 10);

        final PercentileRanks percentiles = searchResponse.getAggregations().get("percentile_ranks");
        assertConsistent(pcts, percentiles, minValues - 1, maxValues - 1);
    }

    @Test
    public void testOrderBySubAggregation() {
        boolean asc = randomBoolean();
        SearchResponse searchResponse = client().prepareSearch("idx")
                .setQuery(matchAllQuery())
                .addAggregation(
                        histogram("histo").field("value").interval(2l)
                            .subAggregation(randomCompression(percentileRanks("percentile_ranks").percentiles(99)))
                            .order(Order.aggregation("percentile_ranks", "99", asc)))
                .execute().actionGet();

        assertHitCount(searchResponse, 10);

        Histogram histo = searchResponse.getAggregations().get("histo");
        double previous = asc ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY;
        for (Histogram.Bucket bucket : histo.getBuckets()) {
            PercentileRanks percentiles = bucket.getAggregations().get("percentile_ranks");
            double p99 = percentiles.percent(99);
            if (asc) {
                assertThat(p99, greaterThanOrEqualTo(previous));
            } else {
                assertThat(p99, lessThanOrEqualTo(previous));
            }
            previous = p99;
        }
    }

}
