/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.solr.handler.component;

import static org.apache.solr.common.params.CursorMarkParams.CURSOR_MARK_NEXT;
import static org.apache.solr.common.params.CursorMarkParams.CURSOR_MARK_PARAM;
import static org.apache.solr.common.params.CursorMarkParams.CURSOR_MARK_START;
import static org.apache.solr.common.util.Utils.fromJSONString;

import com.carrotsearch.randomizedtesting.rules.SystemPropertiesRestoreRule;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.lang.invoke.MethodHandles;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.util.BytesRef;
import org.apache.solr.SolrTestCaseJ4;
import org.apache.solr.common.params.CommonParams;
import org.apache.solr.common.params.CursorMarkParams;
import org.apache.solr.common.params.GroupParams;
import org.apache.solr.common.params.QueryElevationParams;
import org.apache.solr.common.params.SolrParams;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.core.SolrCore;
import org.apache.solr.request.SolrQueryRequest;
import org.apache.solr.search.CollapsingQParserPlugin;
import org.apache.solr.search.SolrIndexSearcher;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.RuleChain;
import org.junit.rules.TestRule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class QueryElevationComponentTest extends SolrTestCaseJ4 {

  @Rule public TestRule solrTestRules = RuleChain.outerRule(new SystemPropertiesRestoreRule());

  private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

  @BeforeClass
  public static void beforeClass() {
    switch (random().nextInt(3)) {
      case 0:
        System.setProperty("solr.tests.id.stored", "true");
        System.setProperty("solr.tests.id.docValues", "true");
        break;
      case 1:
        System.setProperty("solr.tests.id.stored", "true");
        System.setProperty("solr.tests.id.docValues", "false");
        break;
      case 2:
        System.setProperty("solr.tests.id.stored", "false");
        System.setProperty("solr.tests.id.docValues", "true");
        break;
      default:
        fail("Bad random number generated not between 0-2 inclusive");
        break;
    }
  }

  @Before
  @Override
  public void setUp() throws Exception {
    super.setUp();
  }

  private void init(String schema) throws Exception {
    init("solrconfig-elevate.xml", schema);
  }

  private void init(String config, String schema) throws Exception {
    initCore(config, schema);
    clearIndex();
    assertU(commit());
  }

  // TODO should be @After ?
  private void delete() {
    deleteCore();
  }

  @Test
  public void testFieldType() throws Exception {
    try {
      init("schema11.xml");
      clearIndex();
      assertU(commit());
      assertU(adoc("id", "1", "text", "XXXX XXXX", "str_s", "a"));
      assertU(adoc("id", "2", "text", "YYYY", "str_s", "b"));
      assertU(adoc("id", "3", "text", "ZZZZ", "str_s", "c"));

      assertU(adoc("id", "4", "text", "XXXX XXXX", "str_s", "x"));
      assertU(adoc("id", "5", "text", "YYYY YYYY", "str_s", "y"));
      assertU(adoc("id", "6", "text", "XXXX XXXX", "str_s", "z"));
      assertU(adoc("id", "7", "text", "AAAA", "str_s", "a"));
      assertU(adoc("id", "8", "text", "AAAA", "str_s", "a"));
      assertU(adoc("id", "9", "text", "AAAA AAAA", "str_s", "a"));
      assertU(commit());

      assertQ(
          "",
          req(
              CommonParams.Q,
              "AAAA",
              CommonParams.QT,
              "/elevate",
              CommonParams.FL,
              "id, score, [elevated]"),
          "//*[@numFound='3']",
          "//result/doc[1]/str[@name='id'][.='7']",
          "//result/doc[2]/str[@name='id'][.='9']",
          "//result/doc[3]/str[@name='id'][.='8']",
          "//result/doc[1]/bool[@name='[elevated]'][.='true']",
          "//result/doc[2]/bool[@name='[elevated]'][.='false']",
          "//result/doc[3]/bool[@name='[elevated]'][.='false']");
    } finally {
      delete();
    }
  }

  @Test
  public void testGroupedQuery() throws Exception {
    try {
      init("schema11.xml");
      clearIndex();
      assertU(commit());
      assertU(adoc("id", "1", "text", "XXXX XXXX", "str_s", "a"));
      assertU(adoc("id", "2", "text", "XXXX AAAA", "str_s", "b"));
      assertU(adoc("id", "3", "text", "ZZZZ", "str_s", "c"));
      assertU(adoc("id", "4", "text", "XXXX ZZZZ", "str_s", "d"));
      assertU(adoc("id", "5", "text", "ZZZZ ZZZZ", "str_s", "e"));
      assertU(adoc("id", "6", "text", "AAAA AAAA AAAA", "str_s", "f"));
      assertU(adoc("id", "7", "text", "AAAA AAAA ZZZZ", "str_s", "g"));
      assertU(adoc("id", "8", "text", "XXXX", "str_s", "h"));
      assertU(adoc("id", "9", "text", "YYYY ZZZZ", "str_s", "i"));

      assertU(adoc("id", "22", "text", "XXXX ZZZZ AAAA", "str_s", "b"));
      assertU(adoc("id", "66", "text", "XXXX ZZZZ AAAA", "str_s", "f"));
      assertU(adoc("id", "77", "text", "XXXX ZZZZ AAAA", "str_s", "g"));

      assertU(commit());

      final String groups = "//arr[@name='groups']";

      assertQ(
          "non-elevated group query",
          req(
              CommonParams.Q, "AAAA",
              CommonParams.QT, "/elevate",
              GroupParams.GROUP_FIELD, "str_s",
              GroupParams.GROUP, "true",
              GroupParams.GROUP_TOTAL_COUNT, "true",
              GroupParams.GROUP_LIMIT, "100",
              QueryElevationParams.ENABLE, "false",
              CommonParams.FL, "id, score, [elevated]"),
          "//*[@name='ngroups'][.='3']",
          "//*[@name='matches'][.='6']",
          groups + "/lst[1]//doc[1]/str[@name='id'][.='6']",
          groups + "/lst[1]//doc[1]/bool[@name='[elevated]'][.='false']",
          groups + "/lst[1]//doc[2]/str[@name='id'][.='66']",
          groups + "/lst[1]//doc[2]/bool[@name='[elevated]'][.='false']",
          groups + "/lst[2]//doc[1]/str[@name='id'][.='7']",
          groups + "/lst[2]//doc[1]/bool[@name='[elevated]'][.='false']",
          groups + "/lst[2]//doc[2]/str[@name='id'][.='77']",
          groups + "/lst[2]//doc[2]/bool[@name='[elevated]'][.='false']",
          groups + "/lst[3]//doc[1]/str[@name='id'][.='2']",
          groups + "/lst[3]//doc[1]/bool[@name='[elevated]'][.='false']",
          groups + "/lst[3]//doc[2]/str[@name='id'][.='22']",
          groups + "/lst[3]//doc[2]/bool[@name='[elevated]'][.='false']");

      assertQ(
          "elevated group query",
          req(
              CommonParams.Q, "AAAA",
              CommonParams.QT, "/elevate",
              GroupParams.GROUP_FIELD, "str_s",
              GroupParams.GROUP, "true",
              GroupParams.GROUP_TOTAL_COUNT, "true",
              GroupParams.GROUP_LIMIT, "100",
              CommonParams.FL, "id, score, [elevated]"),
          "//*[@name='ngroups'][.='3']",
          "//*[@name='matches'][.='6']",
          groups + "/lst[1]//doc[1]/str[@name='id'][.='7']",
          groups + "/lst[1]//doc[1]/bool[@name='[elevated]'][.='true']",
          groups + "/lst[1]//doc[2]/str[@name='id'][.='77']",
          groups + "/lst[1]//doc[2]/bool[@name='[elevated]'][.='false']",
          groups + "/lst[2]//doc[1]/str[@name='id'][.='6']",
          groups + "/lst[2]//doc[1]/bool[@name='[elevated]'][.='false']",
          groups + "/lst[2]//doc[2]/str[@name='id'][.='66']",
          groups + "/lst[2]//doc[2]/bool[@name='[elevated]'][.='false']",
          groups + "/lst[3]//doc[1]/str[@name='id'][.='2']",
          groups + "/lst[3]//doc[1]/bool[@name='[elevated]'][.='false']",
          groups + "/lst[3]//doc[2]/str[@name='id'][.='22']",
          groups + "/lst[3]//doc[2]/bool[@name='[elevated]'][.='false']");

      assertQ(
          "non-elevated because sorted group query",
          req(
              CommonParams.Q, "AAAA",
              CommonParams.QT, "/elevate",
              CommonParams.SORT, "id asc",
              GroupParams.GROUP_FIELD, "str_s",
              GroupParams.GROUP, "true",
              GroupParams.GROUP_TOTAL_COUNT, "true",
              GroupParams.GROUP_LIMIT, "100",
              CommonParams.FL, "id, score, [elevated]"),
          "//*[@name='ngroups'][.='3']",
          "//*[@name='matches'][.='6']",
          groups + "/lst[1]//doc[1]/str[@name='id'][.='2']",
          groups + "/lst[1]//doc[1]/bool[@name='[elevated]'][.='false']",
          groups + "/lst[1]//doc[2]/str[@name='id'][.='22']",
          groups + "/lst[1]//doc[2]/bool[@name='[elevated]'][.='false']",
          groups + "/lst[2]//doc[1]/str[@name='id'][.='6']",
          groups + "/lst[2]//doc[1]/bool[@name='[elevated]'][.='false']",
          groups + "/lst[2]//doc[2]/str[@name='id'][.='66']",
          groups + "/lst[2]//doc[2]/bool[@name='[elevated]'][.='false']",
          groups + "/lst[3]//doc[1]/str[@name='id'][.='7']",
          groups + "/lst[3]//doc[1]/bool[@name='[elevated]'][.='true']",
          groups + "/lst[3]//doc[2]/str[@name='id'][.='77']",
          groups + "/lst[3]//doc[2]/bool[@name='[elevated]'][.='false']");

      assertQ(
          "force-elevated sorted group query",
          req(
              CommonParams.Q, "AAAA",
              CommonParams.QT, "/elevate",
              CommonParams.SORT, "id asc",
              QueryElevationParams.FORCE_ELEVATION, "true",
              GroupParams.GROUP_FIELD, "str_s",
              GroupParams.GROUP, "true",
              GroupParams.GROUP_TOTAL_COUNT, "true",
              GroupParams.GROUP_LIMIT, "100",
              CommonParams.FL, "id, score, [elevated]"),
          "//*[@name='ngroups'][.='3']",
          "//*[@name='matches'][.='6']",
          groups + "/lst[1]//doc[1]/str[@name='id'][.='7']",
          groups + "/lst[1]//doc[1]/bool[@name='[elevated]'][.='true']",
          groups + "/lst[1]//doc[2]/str[@name='id'][.='77']",
          groups + "/lst[1]//doc[2]/bool[@name='[elevated]'][.='false']",
          groups + "/lst[2]//doc[1]/str[@name='id'][.='2']",
          groups + "/lst[2]//doc[1]/bool[@name='[elevated]'][.='false']",
          groups + "/lst[2]//doc[2]/str[@name='id'][.='22']",
          groups + "/lst[2]//doc[2]/bool[@name='[elevated]'][.='false']",
          groups + "/lst[3]//doc[1]/str[@name='id'][.='6']",
          groups + "/lst[3]//doc[1]/bool[@name='[elevated]'][.='false']",
          groups + "/lst[3]//doc[2]/str[@name='id'][.='66']",
          groups + "/lst[3]//doc[2]/bool[@name='[elevated]'][.='false']");

      assertQ(
          "non-elevated because of sort within group query",
          req(
              CommonParams.Q, "AAAA",
              CommonParams.QT, "/elevate",
              CommonParams.SORT, "id asc",
              GroupParams.GROUP_SORT, "id desc",
              GroupParams.GROUP_FIELD, "str_s",
              GroupParams.GROUP, "true",
              GroupParams.GROUP_TOTAL_COUNT, "true",
              GroupParams.GROUP_LIMIT, "100",
              CommonParams.FL, "id, score, [elevated]"),
          "//*[@name='ngroups'][.='3']",
          "//*[@name='matches'][.='6']",
          groups + "/lst[1]//doc[1]/str[@name='id'][.='22']",
          groups + "/lst[1]//doc[1]/bool[@name='[elevated]'][.='false']",
          groups + "/lst[1]//doc[2]/str[@name='id'][.='2']",
          groups + "/lst[1]//doc[2]/bool[@name='[elevated]'][.='false']",
          groups + "/lst[2]//doc[1]/str[@name='id'][.='66']",
          groups + "/lst[2]//doc[1]/bool[@name='[elevated]'][.='false']",
          groups + "/lst[2]//doc[2]/str[@name='id'][.='6']",
          groups + "/lst[2]//doc[2]/bool[@name='[elevated]'][.='false']",
          groups + "/lst[3]//doc[1]/str[@name='id'][.='77']",
          groups + "/lst[3]//doc[1]/bool[@name='[elevated]'][.='false']",
          groups + "/lst[3]//doc[2]/str[@name='id'][.='7']",
          groups + "/lst[3]//doc[2]/bool[@name='[elevated]'][.='true']");

      assertQ(
          "force elevated sort within sorted group query",
          req(
              CommonParams.Q, "AAAA",
              CommonParams.QT, "/elevate",
              CommonParams.SORT, "id asc",
              GroupParams.GROUP_SORT, "id desc",
              QueryElevationParams.FORCE_ELEVATION, "true",
              GroupParams.GROUP_FIELD, "str_s",
              GroupParams.GROUP, "true",
              GroupParams.GROUP_TOTAL_COUNT, "true",
              GroupParams.GROUP_LIMIT, "100",
              CommonParams.FL, "id, score, [elevated]"),
          "//*[@name='ngroups'][.='3']",
          "//*[@name='matches'][.='6']",
          groups + "/lst[1]//doc[1]/str[@name='id'][.='7']",
          groups + "/lst[1]//doc[1]/bool[@name='[elevated]'][.='true']",
          groups + "/lst[1]//doc[2]/str[@name='id'][.='77']",
          groups + "/lst[1]//doc[2]/bool[@name='[elevated]'][.='false']",
          groups + "/lst[2]//doc[1]/str[@name='id'][.='22']",
          groups + "/lst[2]//doc[1]/bool[@name='[elevated]'][.='false']",
          groups + "/lst[2]//doc[2]/str[@name='id'][.='2']",
          groups + "/lst[2]//doc[2]/bool[@name='[elevated]'][.='false']",
          groups + "/lst[3]//doc[1]/str[@name='id'][.='66']",
          groups + "/lst[3]//doc[1]/bool[@name='[elevated]'][.='false']",
          groups + "/lst[3]//doc[2]/str[@name='id'][.='6']",
          groups + "/lst[3]//doc[2]/bool[@name='[elevated]'][.='false']");

    } finally {
      delete();
    }
  }

  @Test
  public void testTrieFieldType() throws Exception {
    try {
      init("schema.xml");
      clearIndex();
      assertU(commit());
      assertU(adoc("id", "1", "text", "XXXX XXXX", "str_s", "a"));
      assertU(adoc("id", "2", "text", "YYYY", "str_s", "b"));
      assertU(adoc("id", "3", "text", "ZZZZ", "str_s", "c"));

      assertU(adoc("id", "4", "text", "XXXX XXXX", "str_s", "x"));
      assertU(adoc("id", "5", "text", "YYYY YYYY", "str_s", "y"));
      assertU(adoc("id", "6", "text", "XXXX XXXX", "str_s", "z"));
      assertU(adoc("id", "7", "text", "AAAA", "str_s", "a"));
      assertU(adoc("id", "8", "text", "AAAA", "str_s", "a"));
      assertU(adoc("id", "9", "text", "AAAA AAAA", "str_s", "a"));
      assertU(commit());

      assertQ(
          "",
          req(
              CommonParams.Q,
              "AAAA",
              CommonParams.QT,
              "/elevate",
              CommonParams.FL,
              "id, score, [elevated]"),
          "//*[@numFound='3']",
          "//result/doc[1]/str[@name='id'][.='7']",
          "//result/doc[2]/str[@name='id'][.='8']",
          "//result/doc[3]/str[@name='id'][.='9']",
          "//result/doc[1]/bool[@name='[elevated]'][.='true']",
          "//result/doc[2]/bool[@name='[elevated]'][.='false']",
          "//result/doc[3]/bool[@name='[elevated]'][.='false']");
    } finally {
      delete();
    }
  }

  @Test
  public void testInterface() throws Exception {
    try {
      init("schema12.xml");
      SolrCore core = h.getCore();

      NamedList<String> args = new NamedList<>();
      args.add(QueryElevationComponent.FIELD_TYPE, "string");
      args.add(QueryElevationComponent.CONFIG_FILE, "elevate.xml");

      IndexReader reader;
      try (SolrQueryRequest req = req()) {
        reader = req.getSearcher().getIndexReader();
      }

      try (QueryElevationComponent comp = new QueryElevationComponent()) {
        comp.init(args);
        comp.inform(core);

        QueryElevationComponent.ElevationProvider elevationProvider =
            comp.getElevationProvider(reader, core);

        // Make sure the boosts loaded properly
        assertEquals(11, elevationProvider.size());
        assertEquals(1, elevationProvider.getElevationForQuery("XXXX").elevatedIds.size());
        assertEquals(2, elevationProvider.getElevationForQuery("YYYY").elevatedIds.size());
        assertEquals(3, elevationProvider.getElevationForQuery("ZZZZ").elevatedIds.size());
        assertNull(elevationProvider.getElevationForQuery("xxxx"));
        assertNull(elevationProvider.getElevationForQuery("yyyy"));
        assertNull(elevationProvider.getElevationForQuery("zzzz"));
      }

      // Now test the same thing with a lowercase filter: 'lowerfilt'
      args = new NamedList<>();
      args.add(QueryElevationComponent.FIELD_TYPE, "lowerfilt");
      args.add(QueryElevationComponent.CONFIG_FILE, "elevate.xml");

      try (QueryElevationComponent comp = new QueryElevationComponent()) {
        comp.init(args);
        comp.inform(core);
        QueryElevationComponent.ElevationProvider elevationProvider =
            comp.getElevationProvider(reader, core);
        assertEquals(11, elevationProvider.size());
        assertEquals(1, elevationProvider.getElevationForQuery("XXXX").elevatedIds.size());
        assertEquals(2, elevationProvider.getElevationForQuery("YYYY").elevatedIds.size());
        assertEquals(3, elevationProvider.getElevationForQuery("ZZZZ").elevatedIds.size());
        assertEquals(1, elevationProvider.getElevationForQuery("xxxx").elevatedIds.size());
        assertEquals(2, elevationProvider.getElevationForQuery("yyyy").elevatedIds.size());
        assertEquals(3, elevationProvider.getElevationForQuery("zzzz").elevatedIds.size());

        assertEquals("xxxx", comp.analyzeQuery("XXXX"));
        assertEquals("xxxxyyyy", comp.analyzeQuery("XXXX YYYY"));

        assertQ(
            "Make sure QEC handles null queries",
            req("qt", "/elevate", "q.alt", "*:*", "defType", "dismax"),
            "//*[@numFound='0']");
      }
    } finally {
      delete();
    }
  }

  @Test
  public void testMarker() throws Exception {
    try {
      init("schema12.xml");
      assertU(adoc("id", "1", "title", "XXXX XXXX", "str_s1", "a"));
      assertU(adoc("id", "2", "title", "YYYY", "str_s1", "b"));
      assertU(adoc("id", "3", "title", "ZZZZ", "str_s1", "c"));

      assertU(adoc("id", "4", "title", "XXXX XXXX", "str_s1", "x"));
      assertU(adoc("id", "5", "title", "YYYY YYYY", "str_s1", "y"));
      assertU(adoc("id", "6", "title", "XXXX XXXX", "str_s1", "z"));
      assertU(adoc("id", "7", "title", "AAAA", "str_s1", "a"));
      assertU(commit());

      assertQ(
          "",
          req(
              CommonParams.Q,
              "XXXX",
              CommonParams.QT,
              "/elevate",
              CommonParams.FL,
              "id, score, [elevated]"),
          "//*[@numFound='3']",
          "//result/doc[1]/str[@name='id'][.='1']",
          "//result/doc[2]/str[@name='id'][.='4']",
          "//result/doc[3]/str[@name='id'][.='6']",
          "//result/doc[1]/bool[@name='[elevated]'][.='true']",
          "//result/doc[2]/bool[@name='[elevated]'][.='false']",
          "//result/doc[3]/bool[@name='[elevated]'][.='false']");

      assertQ(
          "",
          req(
              CommonParams.Q,
              "AAAA",
              CommonParams.QT,
              "/elevate",
              CommonParams.FL,
              "id, score, [elevated]"),
          "//*[@numFound='1']",
          "//result/doc[1]/str[@name='id'][.='7']",
          "//result/doc[1]/bool[@name='[elevated]'][.='true']");

      assertQ(
          "",
          req(
              CommonParams.Q,
              "AAAA",
              CommonParams.QT,
              "/elevate",
              CommonParams.FL,
              "id, score, [elev]"),
          "//*[@numFound='1']",
          "//result/doc[1]/str[@name='id'][.='7']",
          "not(//result/doc[1]/bool[@name='[elevated]'][.='false'])",
          "not(//result/doc[1]/bool[@name='[elev]'][.='false'])"
          // even though we asked for elev, there is no Transformer registered w/ that, so we
          // shouldn't get a result
          );
    } finally {
      delete();
    }
  }

  @Test
  public void testMarkExcludes() throws Exception {
    try {
      init("schema12.xml");
      assertU(adoc("id", "1", "title", "XXXX XXXX", "str_s1", "a"));
      assertU(adoc("id", "2", "title", "YYYY", "str_s1", "b"));
      assertU(adoc("id", "3", "title", "ZZZZ", "str_s1", "c"));

      assertU(adoc("id", "4", "title", "XXXX XXXX", "str_s1", "x"));
      assertU(adoc("id", "5", "title", "YYYY YYYY", "str_s1", "y"));
      assertU(adoc("id", "6", "title", "XXXX XXXX", "str_s1", "z"));
      assertU(adoc("id", "7", "title", "AAAA", "str_s1", "a"));

      assertU(adoc("id", "8", "title", " QQQQ trash trash", "str_s1", "q"));
      assertU(adoc("id", "9", "title", " QQQQ QQQQ  trash", "str_s1", "r"));
      assertU(adoc("id", "10", "title", "QQQQ QQQQ  QQQQ ", "str_s1", "s"));

      assertU(commit());

      assertQ(
          "",
          req(
              CommonParams.Q,
              "XXXX XXXX",
              CommonParams.QT,
              "/elevate",
              QueryElevationParams.MARK_EXCLUDES,
              "true",
              "indent",
              "true",
              CommonParams.FL,
              "id, score, [excluded]"),
          "//*[@numFound='4']",
          "//result/doc[1]/str[@name='id'][.='5']",
          "//result/doc[2]/str[@name='id'][.='1']",
          "//result/doc[3]/str[@name='id'][.='4']",
          "//result/doc[4]/str[@name='id'][.='6']",
          "//result/doc[1]/bool[@name='[excluded]'][.='false']",
          "//result/doc[2]/bool[@name='[excluded]'][.='false']",
          "//result/doc[3]/bool[@name='[excluded]'][.='false']",
          "//result/doc[4]/bool[@name='[excluded]'][.='true']");

      // ask for excluded as a field, but don't actually request the MARK_EXCLUDES
      // thus, number 6 should not be returned, b/c it is excluded
      assertQ(
          "",
          req(
              CommonParams.Q,
              "XXXX XXXX",
              CommonParams.QT,
              "/elevate",
              QueryElevationParams.MARK_EXCLUDES,
              "false",
              CommonParams.FL,
              "id, score, [excluded]"),
          "//*[@numFound='3']",
          "//result/doc[1]/str[@name='id'][.='5']",
          "//result/doc[2]/str[@name='id'][.='1']",
          "//result/doc[3]/str[@name='id'][.='4']",
          "//result/doc[1]/bool[@name='[excluded]'][.='false']",
          "//result/doc[2]/bool[@name='[excluded]'][.='false']",
          "//result/doc[3]/bool[@name='[excluded]'][.='false']");

      // test that excluded results are on the same positions in the result list
      // as when elevation component is disabled
      // (i.e. test that elevation component with MARK_EXCLUDES does not boost
      // excluded results)
      assertQ(
          "",
          req(
              CommonParams.Q,
              "QQQQ",
              CommonParams.QT,
              "/elevate",
              QueryElevationParams.ENABLE,
              "false",
              "indent",
              "true",
              CommonParams.FL,
              "id, score"),
          "//*[@numFound='3']",
          "//result/doc[1]/str[@name='id'][.='10']",
          "//result/doc[2]/str[@name='id'][.='9']",
          "//result/doc[3]/str[@name='id'][.='8']");
      assertQ(
          "",
          req(
              CommonParams.Q,
              "QQQQ",
              CommonParams.QT,
              "/elevate",
              QueryElevationParams.MARK_EXCLUDES,
              "true",
              "indent",
              "true",
              CommonParams.FL,
              "id, score, [excluded]"),
          "//*[@numFound='3']",
          "//result/doc[1]/str[@name='id'][.='10']",
          "//result/doc[2]/str[@name='id'][.='9']",
          "//result/doc[3]/str[@name='id'][.='8']",
          "//result/doc[1]/bool[@name='[excluded]'][.='true']",
          "//result/doc[2]/bool[@name='[excluded]'][.='false']",
          "//result/doc[3]/bool[@name='[excluded]'][.='false']");
    } finally {
      delete();
    }
  }

  @Test
  public void testSorting() throws Exception {
    try {
      init("schema12.xml");
      assertU(adoc("id", "a", "title", "ipod trash trash", "str_s1", "group1"));
      assertU(adoc("id", "b", "title", "ipod ipod  trash", "str_s1", "group2"));
      assertU(adoc("id", "c", "title", "ipod ipod  ipod ", "str_s1", "group2"));

      assertU(adoc("id", "x", "title", "boosted", "str_s1", "group1"));
      assertU(adoc("id", "y", "title", "boosted boosted", "str_s1", "group2"));
      assertU(adoc("id", "z", "title", "boosted boosted boosted", "str_s1", "group2"));
      assertU(commit());

      final String query = "title:ipod";

      final SolrParams baseParams =
          params(
              "qt", "/elevate",
              "q", query,
              "fl", "id,score",
              "indent", "true");

      QueryElevationComponent booster =
          (QueryElevationComponent) h.getCore().getSearchComponent("elevate");
      IndexReader reader = h.getCore().withSearcher(SolrIndexSearcher::getIndexReader);

      assertQ(
          "Make sure standard sort works as expected",
          req(baseParams),
          "//*[@numFound='3']",
          "//result/doc[1]/str[@name='id'][.='c']",
          "//result/doc[2]/str[@name='id'][.='b']",
          "//result/doc[3]/str[@name='id'][.='a']");

      // Explicitly set what gets boosted
      booster.setTopQueryResults(reader, query, false, new String[] {"x", "y", "z"}, null);

      assertQ(
          "All six should make it",
          req(baseParams),
          "//*[@numFound='6']",
          "//result/doc[1]/str[@name='id'][.='x']",
          "//result/doc[2]/str[@name='id'][.='y']",
          "//result/doc[3]/str[@name='id'][.='z']",
          "//result/doc[4]/str[@name='id'][.='c']",
          "//result/doc[5]/str[@name='id'][.='b']",
          "//result/doc[6]/str[@name='id'][.='a']");

      // now switch the order:
      booster.setTopQueryResults(reader, query, false, new String[] {"a", "x"}, null);
      assertQ(
          req(baseParams),
          "//*[@numFound='4']",
          "//result/doc[1]/str[@name='id'][.='a']",
          "//result/doc[2]/str[@name='id'][.='x']",
          "//result/doc[3]/str[@name='id'][.='c']",
          "//result/doc[4]/str[@name='id'][.='b']");

      // Try normal sort by 'id'
      // default 'forceBoost' should be false
      assertFalse(booster.forceElevation);
      assertQ(
          req(baseParams, "sort", "id asc"),
          "//*[@numFound='4']",
          "//result/doc[1]/str[@name='id'][.='a']",
          "//result/doc[2]/str[@name='id'][.='b']",
          "//result/doc[3]/str[@name='id'][.='c']",
          "//result/doc[4]/str[@name='id'][.='x']");

      assertQ(
          "useConfiguredElevatedOrder=false",
          req(baseParams, "sort", "str_s1 asc,id desc", "useConfiguredElevatedOrder", "false"),
          "//*[@numFound='4']",
          "//result/doc[1]/str[@name='id'][.='x']", // group1
          "//result/doc[2]/str[@name='id'][.='a']", // group1
          "//result/doc[3]/str[@name='id'][.='c']",
          "//result/doc[4]/str[@name='id'][.='b']");

      booster.forceElevation = true;
      assertQ(
          req(baseParams, "sort", "id asc"),
          "//*[@numFound='4']",
          "//result/doc[1]/str[@name='id'][.='a']",
          "//result/doc[2]/str[@name='id'][.='x']",
          "//result/doc[3]/str[@name='id'][.='b']",
          "//result/doc[4]/str[@name='id'][.='c']");

      booster.forceElevation = true;
      assertQ(
          "useConfiguredElevatedOrder=false and forceElevation",
          req(baseParams, "sort", "id desc", "useConfiguredElevatedOrder", "false"),
          "//*[@numFound='4']",
          "//result/doc[1]/str[@name='id'][.='x']", // force elevated
          "//result/doc[2]/str[@name='id'][.='a']", // force elevated
          "//result/doc[3]/str[@name='id'][.='c']",
          "//result/doc[4]/str[@name='id'][.='b']");

      // Test exclusive (not to be confused with exclusion)
      booster.setTopQueryResults(reader, query, false, new String[] {"x", "a"}, new String[] {});
      assertQ(
          req(baseParams, "exclusive", "true"),
          "//*[@numFound='2']",
          "//result/doc[1]/str[@name='id'][.='x']",
          "//result/doc[2]/str[@name='id'][.='a']");

      // Test exclusion
      booster.setTopQueryResults(reader, query, false, new String[] {"x"}, new String[] {"a"});
      assertQ(
          req(baseParams),
          "//*[@numFound='3']",
          "//result/doc[1]/str[@name='id'][.='x']",
          "//result/doc[2]/str[@name='id'][.='c']",
          "//result/doc[3]/str[@name='id'][.='b']");

      // Test setting ids and excludes from http parameters

      booster.clearElevationProviderCache();
      assertQ(
          "All five should make it",
          req(baseParams, "elevateIds", "x,y,z", "excludeIds", "b"),
          "//*[@numFound='5']",
          "//result/doc[1]/str[@name='id'][.='x']",
          "//result/doc[2]/str[@name='id'][.='y']",
          "//result/doc[3]/str[@name='id'][.='z']",
          "//result/doc[4]/str[@name='id'][.='c']",
          "//result/doc[5]/str[@name='id'][.='a']");

      assertQ(
          "All four should make it",
          req(baseParams, "elevateIds", "x,z,y", "excludeIds", "b,c"),
          "//*[@numFound='4']",
          "//result/doc[1]/str[@name='id'][.='x']",
          "//result/doc[2]/str[@name='id'][.='z']",
          "//result/doc[3]/str[@name='id'][.='y']",
          "//result/doc[4]/str[@name='id'][.='a']");

    } finally {
      delete();
    }
  }

  // write an elevation config file to boost some docs
  private void writeElevationConfigFile(File file, String query, String... ids) throws Exception {
    PrintWriter out =
        new PrintWriter(new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8));
    out.println("<?xml version=\"1.0\" encoding=\"UTF-8\" ?>");
    out.println("<elevate>");
    out.println("<query text=\"" + query + "\">");
    for (String id : ids) {
      out.println(" <doc id=\"" + id + "\"/>");
    }
    out.println("</query>");
    out.println("</elevate>");
    out.flush();
    out.close();

    if (log.isInfoEnabled()) {
      log.info("OUT: {}", file.getAbsolutePath());
    }
  }

  @Test
  public void testElevationReloading() throws Exception {
    // need a mutable solr home.  Copying all collection1 is a lot but this is only one test.
    final Path solrHome = createTempDir();
    copyMinConf(solrHome.resolve("collection1").toFile(), null, "solrconfig-elevate.xml");

    File configFile =
        solrHome.resolve("collection1").resolve("conf").resolve("elevate.xml").toFile();
    writeElevationConfigFile(configFile, "aaa", "A");

    initCore("solrconfig.xml", "schema.xml", solrHome.toString());

    try {

      QueryElevationComponent comp =
          (QueryElevationComponent) h.getCore().getSearchComponent("elevate");
      NamedList<String> args = new NamedList<>();
      args.add(QueryElevationComponent.CONFIG_FILE, configFile.getName());
      comp.init(args);
      comp.inform(h.getCore());

      QueryElevationComponent.ElevationProvider elevationProvider;

      try (SolrQueryRequest req = req()) {
        elevationProvider =
            comp.getElevationProvider(req.getSearcher().getIndexReader(), req.getCore());
        assertTrue(
            elevationProvider.getElevationForQuery("aaa").elevatedIds.contains(new BytesRef("A")));
        assertNull(elevationProvider.getElevationForQuery("bbb"));
      }

      // now change the file
      writeElevationConfigFile(configFile, "bbb", "B");

      // With no index change, we get the same index reader, so the elevationProviderCache returns
      // the previous ElevationProvider without the change.
      try (SolrQueryRequest req = req()) {
        elevationProvider =
            comp.getElevationProvider(req.getSearcher().getIndexReader(), req.getCore());
        assertTrue(
            elevationProvider.getElevationForQuery("aaa").elevatedIds.contains(new BytesRef("A")));
        assertNull(elevationProvider.getElevationForQuery("bbb"));
      }

      // Index a new doc to get a new index reader.
      assertU(adoc("id", "10000"));
      assertU(commit());

      // Check that we effectively reload a new ElevationProvider for a different index reader (so
      // two entries in elevationProviderCache).
      try (SolrQueryRequest req = req()) {
        elevationProvider =
            comp.getElevationProvider(req.getSearcher().getIndexReader(), req.getCore());
        assertNull(elevationProvider.getElevationForQuery("aaa"));
        assertTrue(
            elevationProvider.getElevationForQuery("bbb").elevatedIds.contains(new BytesRef("B")));
      }

      // Now change the config file again.
      writeElevationConfigFile(configFile, "ccc", "C");

      // Without index change, but calling a different method that clears the
      // elevationProviderCache, so we should load a new ElevationProvider.
      int elevationRuleNumber = comp.loadElevationConfiguration(h.getCore());
      assertEquals(1, elevationRuleNumber);
      try (SolrQueryRequest req = req()) {
        elevationProvider =
            comp.getElevationProvider(req.getSearcher().getIndexReader(), req.getCore());
        assertNull(elevationProvider.getElevationForQuery("aaa"));
        assertNull(elevationProvider.getElevationForQuery("bbb"));
        assertTrue(
            elevationProvider.getElevationForQuery("ccc").elevatedIds.contains(new BytesRef("C")));
      }
    } finally {
      delete();
    }
  }

  @Test
  public void testWithLocalParam() throws Exception {
    try {
      init("schema11.xml");
      clearIndex();
      assertU(commit());
      assertU(adoc("id", "7", "text", "AAAA", "str_s", "a"));
      assertU(commit());

      assertQ(
          "",
          req(
              CommonParams.Q,
              "AAAA",
              CommonParams.QT,
              "/elevate",
              CommonParams.FL,
              "id, score, [elevated]"),
          "//*[@numFound='1']",
          "//result/doc[1]/str[@name='id'][.='7']",
          "//result/doc[1]/bool[@name='[elevated]'][.='true']");
      assertQ(
          "",
          req(
              CommonParams.Q,
              "{!q.op=AND}AAAA",
              CommonParams.QT,
              "/elevate",
              CommonParams.FL,
              "id, score, [elevated]"),
          "//*[@numFound='1']",
          "//result/doc[1]/str[@name='id'][.='7']",
          "//result/doc[1]/bool[@name='[elevated]'][.='true']");
      assertQ(
          "",
          req(
              CommonParams.Q,
              "{!q.op=AND v='AAAA'}",
              CommonParams.QT,
              "/elevate",
              CommonParams.FL,
              "id, score, [elevated]"),
          "//*[@numFound='1']",
          "//result/doc[1]/str[@name='id'][.='7']",
          "//result/doc[1]/bool[@name='[elevated]'][.='true']");
    } finally {
      delete();
    }
  }

  @Test
  public void testQuerySubsetMatching() throws Exception {
    try {
      init("schema12.xml");
      assertU(adoc("id", "1", "title", "XXXX", "str_s1", "a"));
      assertU(adoc("id", "2", "title", "YYYY", "str_s1", "b"));
      assertU(adoc("id", "3", "title", "ZZZZ", "str_s1", "c"));

      assertU(adoc("id", "4", "title", "XXXX XXXX", "str_s1", "x"));
      assertU(adoc("id", "5", "title", "YYYY YYYY", "str_s1", "y"));
      assertU(adoc("id", "6", "title", "XXXX XXXX", "str_s1", "z"));
      assertU(adoc("id", "7", "title", "AAAA", "str_s1", "a"));

      assertU(adoc("id", "10", "title", "RR", "str_s1", "r"));
      assertU(adoc("id", "11", "title", "SS", "str_s1", "r"));
      assertU(adoc("id", "12", "title", "TT", "str_s1", "r"));
      assertU(adoc("id", "13", "title", "UU", "str_s1", "r"));
      assertU(adoc("id", "14", "title", "VV", "str_s1", "r"));
      assertU(commit());

      // Exact matching.
      assertQ(
          "",
          req(
              CommonParams.Q,
              "XXXX",
              CommonParams.QT,
              "/elevate",
              CommonParams.FL,
              "id, score, [elevated]"),
          "//*[@numFound='3']",
          "//result/doc[1]/str[@name='id'][.='1']",
          "//result/doc[2]/str[@name='id'][.='4']",
          "//result/doc[3]/str[@name='id'][.='6']",
          "//result/doc[1]/bool[@name='[elevated]'][.='true']",
          "//result/doc[2]/bool[@name='[elevated]'][.='false']",
          "//result/doc[3]/bool[@name='[elevated]'][.='false']");

      // Exact matching.
      assertQ(
          "",
          req(
              CommonParams.Q,
              "QQQQ EE",
              CommonParams.QT,
              "/elevate",
              CommonParams.FL,
              "id, score, [elevated]"),
          "//*[@numFound='0']");

      // Subset matching.
      assertQ(
          "",
          req(
              CommonParams.Q,
              "BB DD CC VV",
              CommonParams.QT,
              "/elevate",
              CommonParams.FL,
              "id, score, [elevated]"),
          "//*[@numFound='4']",
          "//result/doc[1]/str[@name='id'][.='10']",
          "//result/doc[2]/str[@name='id'][.='12']",
          "//result/doc[3]/str[@name='id'][.='11']",
          "//result/doc[4]/str[@name='id'][.='14']",
          "//result/doc[1]/bool[@name='[elevated]'][.='true']",
          "//result/doc[2]/bool[@name='[elevated]'][.='true']",
          "//result/doc[3]/bool[@name='[elevated]'][.='true']",
          "//result/doc[4]/bool[@name='[elevated]'][.='false']");

      // Subset + exact matching.
      assertQ(
          "",
          req(
              CommonParams.Q,
              "BB CC",
              CommonParams.QT,
              "/elevate",
              CommonParams.FL,
              "id, score, [elevated]"),
          "//*[@numFound='4']",
          "//result/doc[1]/str[@name='id'][.='13']",
          "//result/doc[2]/str[@name='id'][.='10']",
          "//result/doc[3]/str[@name='id'][.='12']",
          "//result/doc[4]/str[@name='id'][.='11']",
          "//result/doc[1]/bool[@name='[elevated]'][.='true']",
          "//result/doc[2]/bool[@name='[elevated]'][.='true']",
          "//result/doc[3]/bool[@name='[elevated]'][.='true']",
          "//result/doc[4]/bool[@name='[elevated]'][.='true']");

      // Subset matching.
      assertQ(
          "",
          req(
              CommonParams.Q,
              "AA BB DD CC AA",
              CommonParams.QT,
              "/elevate",
              CommonParams.FL,
              "id, score, [elevated]"),
          "//*[@numFound='4']",
          "//result/doc[1]/str[@name='id'][.='10']",
          "//result/doc[2]/str[@name='id'][.='12']",
          "//result/doc[3]/str[@name='id'][.='11']",
          "//result/doc[4]/str[@name='id'][.='14']",
          "//result/doc[1]/bool[@name='[elevated]'][.='true']",
          "//result/doc[2]/bool[@name='[elevated]'][.='true']",
          "//result/doc[3]/bool[@name='[elevated]'][.='true']",
          "//result/doc[4]/bool[@name='[elevated]'][.='true']");

      // Subset matching.
      assertQ(
          "",
          req(
              CommonParams.Q,
              "AA RR BB DD AA",
              CommonParams.QT,
              "/elevate",
              CommonParams.FL,
              "id, score, [elevated]"),
          "//*[@numFound='3']",
          "//result/doc[1]/str[@name='id'][.='12']",
          "//result/doc[2]/str[@name='id'][.='14']",
          "//result/doc[3]/str[@name='id'][.='10']",
          "//result/doc[1]/bool[@name='[elevated]'][.='true']",
          "//result/doc[2]/bool[@name='[elevated]'][.='true']",
          "//result/doc[3]/bool[@name='[elevated]'][.='false']");

      // Subset matching.
      assertQ(
          "",
          req(
              CommonParams.Q,
              "AA BB EE",
              CommonParams.QT,
              "/elevate",
              CommonParams.FL,
              "id, score, [elevated]"),
          "//*[@numFound='0']");
    } finally {
      delete();
    }
  }

  @Test
  public void testElevatedIds() throws Exception {
    try (QueryElevationComponent comp = new QueryElevationComponent()) {
      init("schema12.xml");
      SolrCore core = h.getCore();

      NamedList<String> args = new NamedList<>();
      args.add(QueryElevationComponent.FIELD_TYPE, "text");
      args.add(QueryElevationComponent.CONFIG_FILE, "elevate.xml");

      comp.init(args);
      comp.inform(core);

      SolrQueryRequest req = req();
      IndexReader reader = req.getSearcher().getIndexReader();
      QueryElevationComponent.ElevationProvider elevationProvider =
          comp.getElevationProvider(reader, core);
      req.close();

      assertEquals(toIdSet("1"), elevationProvider.getElevationForQuery("xxxx").elevatedIds);
      assertEquals(
          toIdSet("10", "11", "12"),
          elevationProvider.getElevationForQuery("bb DD CC vv").elevatedIds);
      assertEquals(
          toIdSet("10", "11", "12", "13"),
          elevationProvider.getElevationForQuery("BB Cc").elevatedIds);
      assertEquals(
          toIdSet("10", "11", "12", "14"),
          elevationProvider.getElevationForQuery("aa bb dd cc aa").elevatedIds);
    } finally {
      delete();
    }
  }

  @Test
  public void testOnlyDocsInSearchResultsWillBeElevated() throws Exception {
    try {
      init("schema12.xml");
      assertU(adoc("id", "1", "title", "XXXX", "str_s1", "a"));
      assertU(adoc("id", "2", "title", "YYYY", "str_s1", "b"));
      assertU(adoc("id", "3", "title", "ZZZZ", "str_s1", "c"));

      assertU(adoc("id", "4", "title", "XXXX XXXX", "str_s1", "x"));
      assertU(adoc("id", "5", "title", "YYYY YYYY", "str_s1", "y"));
      assertU(adoc("id", "6", "title", "XXXX XXXX", "str_s1", "z"));
      assertU(adoc("id", "7", "title", "AAAA", "str_s1", "a"));

      assertU(commit());

      // default behaviour
      assertQ(
          "",
          req(
              CommonParams.Q, "YYYY",
              CommonParams.QT, "/elevate",
              QueryElevationParams.ELEVATE_ONLY_DOCS_MATCHING_QUERY, "false",
              CommonParams.FL, "id, score, [elevated]"),
          "//*[@numFound='3']",
          "//result/doc[1]/str[@name='id'][.='1']",
          "//result/doc[2]/str[@name='id'][.='2']",
          "//result/doc[3]/str[@name='id'][.='5']",
          "//result/doc[1]/bool[@name='[elevated]'][.='true']",
          "//result/doc[2]/bool[@name='[elevated]'][.='true']",
          "//result/doc[3]/bool[@name='[elevated]'][.='false']");

      // only docs that matches q
      assertQ(
          "",
          req(
              CommonParams.Q, "YYYY",
              CommonParams.QT, "/elevate",
              QueryElevationParams.ELEVATE_ONLY_DOCS_MATCHING_QUERY, "true",
              CommonParams.FL, "id, score, [elevated]"),
          "//*[@numFound='2']",
          "//result/doc[1]/str[@name='id'][.='2']",
          "//result/doc[2]/str[@name='id'][.='5']",
          "//result/doc[1]/bool[@name='[elevated]'][.='true']",
          "//result/doc[2]/bool[@name='[elevated]'][.='false']");

    } finally {
      delete();
    }
  }

  @Test
  public void testOnlyRepresentativeIsVisibleWhenCollapsing() throws Exception {
    try {
      init("schema12.xml");
      assertU(adoc("id", "1", "title", "ZZZZ", "str_s1", "a"));
      assertU(adoc("id", "2", "title", "ZZZZ", "str_s1", "b"));
      assertU(adoc("id", "3", "title", "ZZZZ ZZZZ", "str_s1", "a"));
      assertU(adoc("id", "4", "title", "ZZZZ ZZZZ", "str_s1", "c"));

      assertU(commit());

      // default behaviour - all elevated docs are visible
      assertQ(
          "",
          req(
              CommonParams.Q, "ZZZZ",
              CommonParams.QT, "/elevate",
              CollapsingQParserPlugin.COLLECT_ELEVATED_DOCS_WHEN_COLLAPSING, "true",
              CommonParams.FQ, "{!collapse field=str_s1 sort='score desc'}",
              CommonParams.FL, "id, score, [elevated]"),
          "//*[@numFound='4']",
          "//result/doc[1]/str[@name='id'][.='1']",
          "//result/doc[2]/str[@name='id'][.='2']",
          "//result/doc[3]/str[@name='id'][.='3']",
          "//result/doc[4]/str[@name='id'][.='4']",
          "//result/doc[1]/bool[@name='[elevated]'][.='true']",
          "//result/doc[2]/bool[@name='[elevated]'][.='true']",
          "//result/doc[3]/bool[@name='[elevated]'][.='true']",
          "//result/doc[4]/bool[@name='[elevated]'][.='false']");

      // only representative elevated doc visible
      assertQ(
          "",
          req(
              CommonParams.Q, "ZZZZ",
              CommonParams.QT, "/elevate",
              CollapsingQParserPlugin.COLLECT_ELEVATED_DOCS_WHEN_COLLAPSING, "false",
              CommonParams.FQ, "{!collapse field=str_s1 sort='score desc'}",
              CommonParams.FL, "id, score, [elevated]"),
          "//*[@numFound='3']",
          "//result/doc[1]/str[@name='id'][.='2']",
          "//result/doc[2]/str[@name='id'][.='3']",
          "//result/doc[3]/str[@name='id'][.='4']",
          "//result/doc[1]/bool[@name='[elevated]'][.='true']",
          "//result/doc[2]/bool[@name='[elevated]'][.='true']",
          "//result/doc[3]/bool[@name='[elevated]'][.='false']");

    } finally {
      delete();
    }
  }

  @Test
  public void testCursor() throws Exception {
    try {
      init("schema12.xml");

      assertU(adoc("id", "a", "title", "ipod trash trash", "str_s1", "group1"));
      assertU(adoc("id", "b", "title", "ipod ipod  trash", "str_s1", "group2"));
      assertU(adoc("id", "c", "title", "ipod ipod  ipod ", "str_s1", "group2"));

      assertU(adoc("id", "x", "title", "boosted", "str_s1", "group1"));
      assertU(adoc("id", "y", "title", "boosted boosted", "str_s1", "group2"));
      assertU(adoc("id", "z", "title", "boosted boosted boosted", "str_s1", "group2"));
      assertU(commit());

      final SolrParams baseParams =
          params(
              "qt", "/elevate",
              "q", "title:ipod",
              "sort", "score desc, id asc",
              "fl", "id",
              "elevateIds", "x,y,z",
              "excludeIds", "b");

      // sanity check everything returned w/these elevation options...
      assertJQ(
          req(baseParams),
          "/response/numFound==5",
          "/response/start==0",
          "/response/docs==[{'id':'x'},{'id':'y'},{'id':'z'},{'id':'c'},{'id':'a'}]");
      // same query using CURSOR_MARK_START should produce a 'next' cursor...
      assertCursorJQ(
          req(baseParams, CURSOR_MARK_PARAM, CURSOR_MARK_START),
          "/response/numFound==5",
          "/response/start==0",
          "/response/docs==[{'id':'x'},{'id':'y'},{'id':'z'},{'id':'c'},{'id':'a'}]");

      // use a cursor w/rows < 5, then fetch next cursor...
      String nextCursor = null;
      nextCursor =
          assertCursorJQ(
              req(baseParams, CURSOR_MARK_PARAM, CURSOR_MARK_START, "rows", "2"),
              "/response/numFound==5",
              "/response/start==0",
              "/response/docs==[{'id':'x'},{'id':'y'}]");
      nextCursor =
          assertCursorJQ(
              req(baseParams, CURSOR_MARK_PARAM, nextCursor, "rows", "2"),
              "/response/numFound==5",
              "/response/start==0",
              "/response/docs==[{'id':'z'},{'id':'c'}]");
      nextCursor =
          assertCursorJQ(
              req(baseParams, CURSOR_MARK_PARAM, nextCursor, "rows", "2"),
              "/response/numFound==5",
              "/response/start==0",
              "/response/docs==[{'id':'a'}]");
      final String lastCursor = nextCursor;
      nextCursor =
          assertCursorJQ(
              req(baseParams, CURSOR_MARK_PARAM, nextCursor, "rows", "2"),
              "/response/numFound==5",
              "/response/start==0",
              "/response/docs==[]");
      assertEquals(lastCursor, nextCursor);

    } finally {
      delete();
    }
  }

  private static Set<BytesRef> toIdSet(String... ids) {
    return Arrays.stream(ids).map(BytesRef::new).collect(Collectors.toSet());
  }

  /**
   * Asserts that the query matches the specified JSON patterns and then returns the {@link
   * CursorMarkParams#CURSOR_MARK_NEXT} value from the response
   *
   * @see #assertJQ
   */
  private static String assertCursorJQ(SolrQueryRequest req, String... tests) throws Exception {
    String json = assertJQ(req, tests);
    Map<?, ?> rsp = (Map<?, ?>) fromJSONString(json);
    assertTrue(
        "response doesn't contain " + CURSOR_MARK_NEXT + ": " + json,
        rsp.containsKey(CURSOR_MARK_NEXT));
    String next = (String) rsp.get(CURSOR_MARK_NEXT);
    assertNotNull(CURSOR_MARK_NEXT + " is null", next);
    return next;
  }
}
