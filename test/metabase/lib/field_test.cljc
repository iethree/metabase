(ns metabase.lib.field-test
  (:require
   #?@(:cljs ([metabase.test-runner.assert-exprs.approximately-equal]))
   [clojure.test :refer [are deftest is testing use-fixtures]]
   [medley.core :as m]
   [metabase.lib.binning :as lib.binning]
   [metabase.lib.core :as lib]
   [metabase.lib.equality :as lib.equality]
   [metabase.lib.field :as lib.field]
   [metabase.lib.metadata :as lib.metadata]
   [metabase.lib.metadata.calculation :as lib.metadata.calculation]
   [metabase.lib.metadata.ident :as lib.metadata.ident]
   [metabase.lib.options :as lib.options]
   [metabase.lib.schema.expression :as lib.schema.expression]
   [metabase.lib.schema.temporal-bucketing :as lib.schema.temporal-bucketing]
   [metabase.lib.temporal-bucket :as lib.temporal-bucket]
   [metabase.lib.test-metadata :as meta]
   [metabase.lib.test-util :as lib.tu]
   [metabase.lib.test-util.macros :as lib.tu.macros]
   [metabase.lib.test-util.mocks-31368 :as lib.tu.mocks-31368]
   [metabase.lib.types.isa :as lib.types.isa]
   [metabase.lib.util :as lib.util]
   [metabase.util :as u]
   [metabase.util.malli :as mu]))

#?(:cljs (comment metabase.test-runner.assert-exprs.approximately-equal/keep-me))

(use-fixtures :each (fn [thunk]
                      (thunk)))

(defn- grandparent-parent-child-id [field]
  (+ (meta/id :venues :id)
     (case field
       :grandparent 50
       :parent      60
       :child       70)))

(def ^:private grandparent-parent-child-metadata-provider
  "A MetadataProvider for a Table that nested Fields: grandparent, parent, and child"
  (let [grandparent {:lib/type     :metadata/column
                     :name         "grandparent"
                     :display-name "Grandparent"
                     :id           (grandparent-parent-child-id :grandparent)
                     :ident        (u/generate-nano-id)
                     :base-type    :type/Text}
        parent      {:lib/type     :metadata/column
                     :name         "parent"
                     :display-name "Parent"
                     :parent-id    (grandparent-parent-child-id :grandparent)
                     :id           (grandparent-parent-child-id :parent)
                     :ident        (u/generate-nano-id)
                     :base-type    :type/Text}
        child       {:lib/type     :metadata/column
                     :name         "child"
                     :display-name "Child"
                     :parent-id    (grandparent-parent-child-id :parent)
                     :id           (grandparent-parent-child-id :child)
                     :ident        (u/generate-nano-id)
                     :base-type    :type/Text}]
    (lib.tu/mock-metadata-provider
     {:database meta/database
      :tables   [(meta/table-metadata :venues)]
      :fields   (mapv (fn [field-metadata]
                        (merge {:visibility-type :normal
                                :table-id        (meta/id :venues)}
                               field-metadata))
                      [grandparent parent child])})))

(deftest ^:parallel col-info-combine-parent-field-names-test
  (letfn [(col-info [a-field-clause]
            (lib/metadata
             {:lib/type     :mbql/query
              :lib/metadata grandparent-parent-child-metadata-provider
              :database     (meta/id)
              :stages       [{:lib/type     :mbql.stage/mbql
                              :lib/options  {:lib/uuid (str (random-uuid))}
                              :source-table (meta/id :venues)}]}
             -1
             a-field-clause))]
    (testing "For fields with parents we should return them with a combined name including parent's name"
      (is (=? {:table-id          (meta/id :venues)
               :name              "grandparent.parent"
               :parent-id         (grandparent-parent-child-id :grandparent)
               :id                (grandparent-parent-child-id :parent)
               :visibility-type   :normal}
              (col-info [:field {:lib/uuid (str (random-uuid))} (grandparent-parent-child-id :parent)]))))
    (testing "nested-nested fields should include grandparent name (etc)"
      (is (=? {:table-id          (meta/id :venues)
               :name              "grandparent.parent.child"
               :parent-id         (grandparent-parent-child-id :parent)
               :id                (grandparent-parent-child-id :child)
               :visibility-type   :normal}
              (col-info [:field {:lib/uuid (str (random-uuid))} (grandparent-parent-child-id :child)]))))))

(deftest ^:parallel nested-field-display-name-test
  (let [base (lib/query grandparent-parent-child-metadata-provider (meta/table-metadata :venues))]
    (testing  "simple queries"
      (doseq [query [base (lib/append-stage base)]
              :let [cols (lib/visible-columns query)]]
        (is (= ["Grandparent: Parent: Child" "Grandparent" "Grandparent: Parent"]
               (map #(lib/display-name query %) cols)))
        (is (= ["Grandparent: Parent: Child" "Grandparent" "Grandparent: Parent"]
               (map #(lib/display-name query -1 % :long) cols)))
        (is (=? [{:display-name "Grandparent: Parent: Child"
                  :long-display-name "Grandparent: Parent: Child"}
                 {:display-name "Grandparent"
                  :long-display-name "Grandparent"}
                 {:display-name "Grandparent: Parent"
                  :long-display-name "Grandparent: Parent"}]
                (map #(lib/display-info query -1 %) cols)))))
    (testing "breakout"
      (is (=? [{:display-name "Grandparent: Parent: Child"
                :long-display-name "Grandparent: Parent: Child"}]
              (->> base
                   lib/breakoutable-columns
                   first
                   (lib/breakout base)
                   lib/append-stage
                   lib/visible-columns
                   (map #(lib/display-info base -1 %))))))
    (testing "join"
      (let [join-column (second (lib/visible-columns base))
            base (-> base
                     (lib/join
                      (-> (lib/join-clause
                           base [(lib/= join-column
                                        (lib/with-join-alias join-column
                                                             "alias"))])
                          (lib/with-join-alias "alias"))))]
        (doseq [query [base (lib/append-stage base)]]
          (is (=? [{:display-name "Grandparent: Parent: Child"
                    :long-display-name "Grandparent: Parent: Child"}
                   {:display-name "Grandparent"
                    :long-display-name "Grandparent"}
                   {:display-name "Grandparent: Parent"
                    :long-display-name "Grandparent: Parent"}
                   {:display-name "Grandparent: Parent: Child"
                    :long-display-name "alias → Grandparent: Parent: Child"}
                   {:display-name "Grandparent"
                    :long-display-name "alias → Grandparent"}
                   {:display-name "Grandparent: Parent"
                    :long-display-name "alias → Grandparent: Parent"}]
                  (->> query
                       lib/visible-columns
                       (map #(lib/display-info base -1 %))))))))))

(deftest ^:parallel col-info-field-literals-test
  (testing "field literals should get the information from the matching `:lib/stage-metadata` if it was supplied"
    (is (=? {:name          "sum"
             :display-name  "sum of User ID"
             :base-type     :type/Integer
             :semantic-type :type/FK}
            (lib/metadata
             (lib.tu/native-query)
             -1
             [:field {:lib/uuid (str (random-uuid)), :base-type :type/Integer} "sum"])))))

(deftest ^:parallel joined-field-display-name-test
  (let [query (-> (lib/query meta/metadata-provider (meta/table-metadata :venues))
                  (lib/join (lib/join-clause (meta/table-metadata :categories)
                                             [(lib/= (meta/field-metadata :venues :category-id)
                                                     (meta/field-metadata :categories :id))]))
                  (lib/with-fields [(lib/with-join-alias (meta/field-metadata :categories :name) "Categories")]))
        field (lib.tu/field-clause :categories :name {:join-alias "Categories"})]
    (are [style expected] (= expected
                             (lib/display-name query -1 field style))
      :default "Name"
      :long    "Categories → Name")
    (is (=? {:display-name "Name"}
            (lib/metadata query -1 field)))))

(deftest ^:parallel legacy-query-joined-field-display-name-test
  (testing "Should calculate correct display names for joined fields when source query is a legacy MBQL query (#31368)"
    (doseq [has-result-metadata? [false true]]
      (testing (str "\nHas result metadata? " (pr-str has-result-metadata?))
        (let [query             (lib.tu.mocks-31368/query-with-legacy-source-card has-result-metadata?)
              breakoutable-cols (lib/breakoutable-columns query)
              breakout-col      (m/find-first (fn [col]
                                                (= (:id col) (meta/id :products :category)))
                                              breakoutable-cols)]
          (testing (str "\nbreakoutable-cols =\n" (u/pprint-to-str breakoutable-cols))
            (is (some? breakout-col)))
          (when breakout-col
            (is (=? {:long-display-name "Products → Category"}
                    (lib/display-info query breakout-col)))
            (let [query' (lib/breakout query breakout-col)]
              (is (=? {:stages
                       [{:lib/type    :mbql.stage/mbql
                         :source-card 1
                         :breakout    [[:field
                                        {:join-alias (symbol "nil #_\"key is not present.\"")}
                                        "Products__CATEGORY"]]}]}
                      query'))
              (is (=? [{:name              "CATEGORY"
                        :display-name      (if has-result-metadata?
                                             "Products → Category"
                                             "Category")
                        :long-display-name "Products → Category"
                        :effective-type    :type/Text}]
                      (map #(lib/display-info query' %)
                           (lib/breakouts query')))))))))))

(deftest ^:parallel field-with-temporal-bucket-test
  (let [query (lib/query meta/metadata-provider (meta/table-metadata :checkins))
        field (lib/ref (lib/with-temporal-bucket (meta/field-metadata :checkins :date) :day-of-month))]
    (is (=? [:field {:temporal-unit :day-of-month} (meta/id :checkins :date)]
            field))
    (testing "(lib/temporal-bucket <field-ref>)"
      (is (= {:lib/type :option/temporal-bucketing
              :unit     :day-of-month}
             (lib/temporal-bucket field))))
    (is (= "Date: Day of month"
           (lib/display-name query -1 field)))))

(def ^:private temporal-bucketing-mock-metadata
  "Mock metadata for testing temporal bucketing stuff.

  * Includes a date field where the `:base-type` is `:type/Text`, but `:effective-type` is `:type/Date` because of a
    `:Coercion/ISO8601->Date`, so we can test that `:effective-type` is preserved properly

  * Includes a mocked Field with `:type/Time`"
  (let [date-field        (assoc (meta/field-metadata :people :birth-date)
                                 :base-type         :type/Text
                                 :effective-type    :type/Date
                                 :coercion-strategy :Coercion/ISO8601->Date)
        time-field        (assoc (meta/field-metadata :orders :created-at)
                                 :base-type      :type/Time
                                 :effective-type :type/Time)
        metadata-provider (lib.tu/mock-metadata-provider
                           meta/metadata-provider
                           {:fields [date-field
                                     time-field]})
        query             (lib/query metadata-provider (meta/table-metadata :venues))]
    {:fields            {:date     date-field
                         :datetime (meta/field-metadata :reviews :created-at)
                         :time     time-field}
     :metadata-provider metadata-provider
     :query             query}))

(deftest ^:parallel with-temporal-bucket-test
  (doseq [[unit effective-type] {:month         :type/Date
                                 :month-of-year :type/Integer}
          :let                  [field-metadata (get-in temporal-bucketing-mock-metadata [:fields :date])]
          [what x update-props] [["column metadata" field-metadata           (fn [x f & args] (apply f x args))]
                                 ["field ref"       (lib/ref field-metadata) lib.options/update-options]]
          :let                  [x' (lib/with-temporal-bucket x unit)]]
    (testing (str what " unit = " unit "\n\n" (u/pprint-to-str x') "\n")
      (testing "should calculate correct effective type"
        (is (= effective-type
               (lib/type-of (:query temporal-bucketing-mock-metadata) x'))))
      (testing "lib/temporal-bucket should return the option"
        (is (= {:lib/type :option/temporal-bucketing
                :unit     unit}
               (lib/temporal-bucket x')))
        (testing "should generate a :field ref with correct :temporal-unit"
          (is (=? [:field
                   {:lib/uuid       string?
                    :base-type      :type/Text
                    :effective-type effective-type
                    :temporal-unit  unit}
                   integer?]
                  (lib/ref x')))))
      (testing "remove the temporal unit"
        (let [x'' (lib/with-temporal-bucket x' nil)]
          (is (nil? (lib/temporal-bucket x'')))
          (is (= x
                 (update-props x'' dissoc :metabase.lib.field/original-temporal-unit)))))
      (testing "change the temporal unit, THEN remove it"
        (let [x''  (lib/with-temporal-bucket x' :quarter-of-year)
              x''' (lib/with-temporal-bucket x'' nil)]
          (is (nil? (lib/temporal-bucket x''')))
          (is (= x
                 (update-props x''' dissoc :metabase.lib.field/original-temporal-unit))))))))

(deftest ^:parallel available-temporal-buckets-test
  (doseq [{:keys [metadata expected-options selected-index selected-unit]}
          [{:metadata         (get-in temporal-bucketing-mock-metadata [:fields :date])
            :selected-unit    :month-of-year
            :selected-index   9
            :expected-options (-> lib.temporal-bucket/date-bucket-options
                                  (update 0 dissoc :default)
                                  (assoc-in [2 :default] true))}
           {:metadata         (get-in temporal-bucketing-mock-metadata [:fields :datetime])
            :selected-unit    :month-of-year
            :selected-index   13
            :expected-options (-> lib.temporal-bucket/datetime-bucket-options
                                  (update 2 dissoc :default)
                                  (assoc-in [4 :default] true))}
           {:metadata         (get-in temporal-bucketing-mock-metadata [:fields :time])
            :selected-unit    :minute
            :selected-index   0
            :expected-options lib.temporal-bucket/time-bucket-options}]]
    (testing (str (:base-type metadata) " Field")
      (doseq [[what x] {"column metadata" metadata, "field ref" (lib/ref metadata)}]
        (testing (str what "\n\n" (u/pprint-to-str x))
          (is (= expected-options
                 (lib/available-temporal-buckets (:query temporal-bucketing-mock-metadata) x)))
          (testing "Bucketing with any of the options should work"
            (doseq [expected-option expected-options]
              (is (= {:lib/type :option/temporal-bucketing
                      :unit      (:unit expected-option)}
                     (lib/temporal-bucket (lib/with-temporal-bucket x expected-option))))))
          (let [bucketed (lib/with-temporal-bucket x selected-unit)
                query2   (lib/breakout (:query temporal-bucketing-mock-metadata) bucketed)]
            (testing "Bucket it, should still return the same available units, with :selected"
              (is (= (assoc-in expected-options [selected-index :selected] true)
                     (lib/available-temporal-buckets query2 bucketed))))
            (testing "shows :selected in display-info"
              (let [options (lib/available-temporal-buckets query2 bucketed)]
                (is (= (-> (count options)
                           (repeat nil)
                           vec
                           (assoc selected-index true))
                       (for [option options]
                         (:selected (lib/display-info query2 option)))))))))))))

(deftest ^:parallel field-with-binning-test
  (let [query         (lib/query meta/metadata-provider (meta/table-metadata :orders))
        binning       {:strategy :num-bins
                       :num-bins 10}
        binning-typed (assoc binning
                             :lib/type    ::lib.binning/binning
                             :metadata-fn fn?)
        field         (lib/ref (lib/with-binning (meta/field-metadata :orders :subtotal) binning))]
    (is (=? [:field {:binning binning} (meta/id :orders :subtotal)]
            field))
    (testing "(lib/binning <column-metadata>)"
      (is (=? binning-typed
              (lib/binning (lib/metadata query -1 field)))))
    (testing "(lib/binning <field-ref>)"
      (is (=? binning-typed
              (lib/binning field))))
    #?(:clj
       ;; i18n/trun doesn't work in the CLJS tests, only in proper FE, so this test is JVM-only.
       (is (= "Subtotal: 10 bins"
              (lib/display-name query -1 field))))))

(deftest ^:parallel with-binning-test
  (doseq [[binning1 binning2] (partition 2 1 [{:strategy :default}
                                              {:strategy :num-bins  :num-bins  10}
                                              {:strategy :bin-width :bin-width 1.0}
                                              {:strategy :default}])
          :let                  [field-metadata (meta/field-metadata :orders :subtotal)]
          [what x]              {"column metadata" field-metadata
                                 "field ref"       (lib/ref field-metadata)}
          :let                  [x' (lib/with-binning x binning1)]]
    (testing (str what " strategy = " (:strategy binning2) "\n\n" (u/pprint-to-str x') "\n")
      (testing "lib/binning should return the binning settings"
        (is (=? (merge binning1
                       {:lib/type    ::lib.binning/binning
                        :metadata-fn fn?})
                (lib/binning x'))))
      (testing "should generate a :field ref with correct :binning"
        (is (=? [:field
                 {:lib/uuid string?
                  :binning  binning1}
                 integer?]
                (lib/ref x'))))
      (testing "remove the binning setting"
        (let [x'' (lib/with-binning x' nil)]
          (is (nil? (lib/binning x'')))
          (is (= x
                 x''))))
      (testing "change the binning setting, THEN remove it"
        (let [x''  (lib/with-binning x' binning2)
              x''' (lib/with-binning x'' nil)]
          (is (=? (merge binning2
                         {:lib/type    ::lib.binning/binning
                          :metadata-fn fn?})
                  (lib/binning x'')))
          (is (nil? (lib/binning x''')))
          (is (= x
                 x''')))))))

(deftest ^:parallel available-binning-strategies-test
  (doseq [{:keys [expected-options field-metadata query]}
          [{:query            (lib/query meta/metadata-provider (meta/table-metadata :orders))
            :field-metadata   (meta/field-metadata :orders :subtotal)
            :expected-options (lib.binning/numeric-binning-strategies)}
           {:query            (lib/query meta/metadata-provider (meta/table-metadata :people))
            :field-metadata   (meta/field-metadata :people :latitude)
            :expected-options (lib.binning/coordinate-binning-strategies)}]]
    (testing (str (:semantic-type field-metadata) " Field")
      (doseq [[what x] [["column metadata" field-metadata]
                        ["field ref"       (lib/ref field-metadata)]]]
        (testing (str what "\n\n" (u/pprint-to-str x))
          (is (= expected-options
                 (lib/available-binning-strategies query x)))
          (let [binned (lib/with-binning x (second expected-options))
                query2 (lib/breakout query binned)]
            (testing "when binned, should return the same available units, with :selected apart from the default"
              (is (= (-> expected-options second :mbql)
                     (-> binned lib/binning (dissoc :lib/type :metadata-fn))))
              (is (= (-> expected-options
                         (assoc-in [1 :selected] true)
                         (m/dissoc-in [0 :default]))
                     (lib/available-binning-strategies query2 binned))))
            (testing "shows :selected in display-info"
              (let [options (lib/available-binning-strategies query2 binned)]
                (is (= (-> options
                           count
                           (repeat nil)
                           vec
                           (assoc 1 true))
                       (for [option options]
                         (:selected (lib/display-info query2 option)))))))))))))

(deftest ^:parallel available-binning-strategies-missing-feature-test
  (let [provider (meta/updated-metadata-provider update :features disj :binning)
        query    (lib/query provider (meta/table-metadata :orders))
        subtotal (meta/field-metadata :orders :subtotal)]
    (testing "no available binning strategies if database does not support :binning"
      (is (= []
             (lib/available-binning-strategies query subtotal))))))

(deftest ^:parallel available-binning-strategies-expressions-test
  (testing "There should be no binning strategies for expressions as they are not supported (#31367)"
    (let [query (-> (lib.tu/venues-query)
                    (lib/expression "myadd" (lib/+ 1 (meta/field-metadata :venues :category-id))))]
      (is (empty? (->> (lib/returned-columns query)
                       (m/find-first (comp #{"myadd"} :name))
                       (lib/available-binning-strategies query)))))))

(deftest ^:parallel binning-display-info-test
  (testing "numeric binning"
    (let [query          (lib/query meta/metadata-provider (meta/table-metadata :orders))
          field-metadata (meta/field-metadata :orders :subtotal)
          strategies     (lib.binning/numeric-binning-strategies)]
      (doseq [[strat exp] (zipmap strategies [{:display-name "Auto binned" :default true}
                                              {:display-name "10 bins"}
                                              {:display-name "50 bins"}
                                              {:display-name "100 bins"}
                                              nil])]
        (is (= exp
               (some->> strat
                        (lib.binning/with-binning field-metadata)
                        lib.binning/binning
                        (lib/display-info query)))))))

  (testing "coordinate binning"
    (let [query          (lib/query meta/metadata-provider (meta/table-metadata :people))
          field-metadata (meta/field-metadata :people :latitude)
          strategies     (lib.binning/coordinate-binning-strategies)]
      (doseq [[strat exp] (zipmap strategies [{:display-name "Auto binned" :default true}
                                              {:display-name "0.1°"}
                                              {:display-name "1°"}
                                              {:display-name "10°"}
                                              {:display-name "20°"}
                                              {:display-name "0.05°"}
                                              {:display-name "0.01°"}
                                              {:display-name "0.005°"}
                                              nil])]
        (is (= exp
               (some->> strat
                        (lib.binning/with-binning field-metadata)
                        lib.binning/binning
                        (lib/display-info query))))))))

(deftest ^:parallel joined-field-column-name-test
  (let [legacy-query {:database (meta/id)
                      :type     :query
                      :query    {:source-table (meta/id :venues)
                                 :joins        [{:fields       :all
                                                 :source-table (meta/id :categories)
                                                 :condition    [:=
                                                                [:field (meta/id :venues :category-id) nil]
                                                                [:field (meta/id :categories :id) {:join-alias "Cat"}]]
                                                 :ident        "khQz-1AxQ4MVUfynQFnUw"
                                                 :alias        "Cat"}]}}
        query        (lib/query meta/metadata-provider legacy-query)]
    (is (=? [{:lib/desired-column-alias "ID"}
             {:lib/desired-column-alias "NAME"}
             {:lib/desired-column-alias "CATEGORY_ID"}
             {:lib/desired-column-alias "LATITUDE"}
             {:lib/desired-column-alias "LONGITUDE"}
             {:lib/desired-column-alias "PRICE"}
             {:lib/desired-column-alias "Cat__ID"}
             {:lib/desired-column-alias "Cat__NAME"}]
            (lib/returned-columns query)))))

(deftest ^:parallel field-ref-type-of-test
  (testing "Make sure we can calculate field ref type information correctly"
    (let [clause [:field {:lib/uuid (str (random-uuid))} (meta/id :venues :id)]]
      (is (= ::lib.schema.expression/type.unknown
             (lib.schema.expression/type-of clause)))
      (is (= :type/BigInteger
             (lib/type-of (lib.tu/venues-query) clause))))))

(deftest ^:parallel implicitly-joinable-field-display-name-test
  (testing "Should be able to calculate a display name for an implicitly joinable Field"
    (let [query           (lib.tu/venues-query)
          categories-name (m/find-first #(= (:id %) (meta/id :categories :name))
                                        (lib/orderable-columns query))]
      (are [style expected] (= expected
                               (lib/display-name query -1 categories-name style))
        :default "Name"
        :long    "Category → Name")
      (let [query' (lib/order-by query categories-name)]
        (testing "Implicitly joinable columns should NOT be given a join alias"
          (is (=? {:stages [{:order-by [[:asc {} [:field
                                                  (complement :join-alias)
                                                  (meta/id :categories :name)]]]}]}
                  query')))
        (is (= "Venues, Sorted by Category → Name ascending"
               (lib/describe-query query'))))
      (testing "inside aggregations"
        (let [query'        (lib/aggregate query (lib/distinct categories-name))
              [aggregation] (lib/aggregations query')]
          (is (= "Venues, Distinct values of Category → Name"
                 (lib/describe-query query')))
          (are [style expected] (= expected
                                   (lib/display-name query' -1 aggregation style))
            :long    "Distinct values of Category → Name"
            :default "Distinct values of Name"))))))

(deftest ^:parallel source-card-table-display-info-test
  ;; this uses a legacy `card__<id>` `:table-id` intentionally; we don't currently have logic that parses this to
  ;; something like `:card-id` for Column Metadata yet. Make sure it works correctly.
  (let [query (assoc (lib.tu/venues-query) :lib/metadata lib.tu/metadata-provider-with-card)
        field (lib/metadata query (assoc (lib.metadata/field query (meta/id :venues :name))
                                         :table-id "card__1"))]
    (is (=? {:name           "NAME"
             :display-name   "Name"
             :semantic-type  :type/Name
             :effective-type :type/Text
             :table          {:name "My Card", :display-name "My Card"}}
            (lib/display-info query field)))))

(deftest ^:parallel resolve-column-name-in-join-test
  (testing ":field refs with string names should work if the Field comes from a :join"
    (let [metadata-provider (lib.tu/metadata-provider-with-cards-for-queries
                             meta/metadata-provider
                             [(lib.tu.macros/mbql-query checkins
                                {:aggregation [[:count]]
                                 :breakout    [$user-id]})])
          cols  (->> (lib.metadata/card metadata-provider 1)
                     (lib/query metadata-provider)
                     lib/returned-columns
                     (m/index-by :name))
          query (-> (lib/query metadata-provider (meta/table-metadata :checkins))
                    (lib/join (lib/with-join-alias
                               (lib/join-clause (lib.metadata/card metadata-provider 1)
                                                [(lib/= (meta/field-metadata :users :id)
                                                        (lib/ref (get cols "USER_ID")))])
                               "checkins_by_user"))
                    (lib/breakout (lib/with-temporal-bucket (meta/field-metadata :users :last-login) :month))
                    (lib/aggregate (lib/avg (lib/with-join-alias (lib/ref (get cols "count")) "checkins_by_user"))))]
      (is (=? [{:id                       (meta/id :users :last-login)
                :name                     "LAST_LOGIN"
                :lib/source               :source/breakouts
                :lib/source-column-alias  "LAST_LOGIN"
                :lib/desired-column-alias "LAST_LOGIN"}
               {:name                     "avg"
                :lib/source               :source/aggregations
                :lib/source-column-alias  "avg"
                :lib/desired-column-alias "avg"}]
              (lib/returned-columns query))))))

(deftest ^:parallel with-fields-test
  (let [query           (-> (lib.tu/venues-query)
                            (lib/expression "myadd" (lib/+ 1 (meta/field-metadata :venues :category-id)))
                            (lib/with-fields [(meta/field-metadata :venues :id) (meta/field-metadata :venues :name)]))
        fields-metadata (fn [query]
                          (map (partial lib/metadata query)
                               (lib/fields query)))
        metadatas       (fields-metadata query)]
    (testing "Expressions should be included in :fields by default (#31236)"
      (is (=? [{:name "ID"}
               {:name "NAME"}
               {:name "myadd"}]
              metadatas)))
    (testing "Set fields with metadatas"
      (let [fields' [(second metadatas)]
            query'  (lib/with-fields query fields')]
        (is (=? [{:name "NAME"}
                 {:name "myadd"}]
                (fields-metadata query')))))
    (testing "remove fields by passing"
      (doseq [new-fields [nil []]]
        (testing (pr-str new-fields)
          (let [query' (lib/with-fields query new-fields)]
            (is (empty? (fields-metadata query')))
            (letfn [(has-fields? [query]
                      (get-in query [:stages 0 :fields]))]
              (is (has-fields? query)
                  "sanity check")
              (is (not (has-fields? query'))))))))))

(deftest ^:parallel with-fields-plus-expression-test
  (let [query           (-> (lib.tu/venues-query)
                            (lib/with-fields [(meta/field-metadata :venues :id)])
                            (lib/expression "myadd" (lib/+ 1 (meta/field-metadata :venues :category-id))))
        fields-metadata (fn [query]
                          (map (partial lib/metadata query)
                               (lib/fields query)))
        metadatas       (fields-metadata query)]
    (testing "Expressions should be included in :fields by default (#31236)"
      (is (=? [{:name "ID"}
               {:name "myadd"}]
              metadatas)))))

(deftest ^:parallel fieldable-columns-test
  (testing "query with no :fields"
    (is (=? [{:lib/desired-column-alias "ID", :selected? true}
             {:lib/desired-column-alias "NAME", :selected? true}
             {:lib/desired-column-alias "CATEGORY_ID", :selected? true}
             {:lib/desired-column-alias "LATITUDE", :selected? true}
             {:lib/desired-column-alias "LONGITUDE", :selected? true}
             {:lib/desired-column-alias "PRICE", :selected? true}]
            (lib/fieldable-columns (lib.tu/venues-query))))))

(deftest ^:parallel fieldable-columns-query-with-fields-test
  (testing "query with :fields"
    (is (=? [{:lib/desired-column-alias "ID", :selected? true}
             {:lib/desired-column-alias "NAME", :selected? true}
             {:lib/desired-column-alias "CATEGORY_ID", :selected? false}
             {:lib/desired-column-alias "LATITUDE", :selected? false}
             {:lib/desired-column-alias "LONGITUDE", :selected? false}
             {:lib/desired-column-alias "PRICE", :selected? false}]
            (-> (lib.tu/venues-query)
                (lib/with-fields [(meta/field-metadata :venues :id)
                                  (meta/field-metadata :venues :name)])
                lib/fieldable-columns)))))

(deftest ^:parallel fallback-metadata-from-saved-question-when-missing-from-metadata-provider-test
  (testing "Handle missing column metadata from the metadata provider; should still work if in Card result metadata (#31624)"
    (let [provider (lib.tu/mock-metadata-provider
                    {:database {:id   1
                                :name "My Database"}
                     :tables   [{:id   2
                                 :name "My Table"}]
                     :cards    [{:id              3
                                 :name            "Card 3"
                                 :database-id     (meta/id)
                                 :dataset-query   {:lib/type :mbql/query
                                                   :database 1
                                                   :stages   [{:lib/type     :mbql.stage/mbql
                                                               :source-table 2}]}
                                 :result-metadata [{:id    4
                                                    :ident "ybTElkkGoYYBAyDRTIiUe"
                                                    :name  "Field 4"}]}]})
          query    (lib/query provider {:lib/type :mbql/query
                                        :database 1
                                        :stages   [{:lib/type    :mbql.stage/mbql
                                                    :source-card 3}]})]
      (is (= [{:lib/type                 :metadata/column
               :base-type                :type/*
               :effective-type           :type/*
               :id                       4
               :name                     "Field 4"
               :ident                    "ybTElkkGoYYBAyDRTIiUe"
               :fk-target-field-id       nil
               :lib/source               :source/card
               :lib/card-id              3
               :lib/source-column-alias  "Field 4"
               :lib/desired-column-alias "Field 4"}]
             (lib/returned-columns query)))
      (is (= {:lib/type                :metadata/column
              :base-type               :type/Text
              :effective-type          :type/Text
              :id                      4
              :name                    "Field 4"
              :ident                   "ybTElkkGoYYBAyDRTIiUe"
              :fk-target-field-id      nil
              :display-name            "Field 4"
              :lib/card-id             3
              :lib/source              :source/card
              :lib/source-column-alias "Field 4"
              :lib/source-uuid         "aa0e13af-29b3-4c27-a880-a10c33e55a3e"}
             (lib/metadata
              query
              [:field {:lib/uuid "aa0e13af-29b3-4c27-a880-a10c33e55a3e", :base-type :type/Text} 4]))))))

(deftest ^:parallel base-type-in-field-ref-does-not-overwrite-everything-test
  (testing "base-type of reference doesn't override a non-default effective-type in the column (#55171)"
    (let [provider (lib.tu/mock-metadata-provider
                    {:database {:id   1
                                :name "My Database"}
                     :tables   [{:id   2
                                 :name "My Table"}]
                     :cards    [{:id              3
                                 :name            "Card 3"
                                 :database-id     (meta/id)
                                 :dataset-query   {:lib/type :mbql/query
                                                   :database 1
                                                   :stages   [{:lib/type     :mbql.stage/mbql
                                                               :source-table 2}]}
                                 :result-metadata [{:id                4
                                                    :base-type         :type/Text
                                                    :effective-type    :type/Date
                                                    :coercion-strategy :Coercion/ISO8601->Date
                                                    :ident             "ybTElkkGoYYBAyDRTIiUe"
                                                    :name              "Field 4"}]}]})
          query    (lib/query provider {:lib/type :mbql/query
                                        :database 1
                                        :stages   [{:lib/type    :mbql.stage/mbql
                                                    :source-card 3}]})]
      (is (= [{:lib/type                 :metadata/column
               :base-type                :type/Text
               :effective-type           :type/Date
               :coercion-strategy        :Coercion/ISO8601->Date
               :id                       4
               :name                     "Field 4"
               :ident                    "ybTElkkGoYYBAyDRTIiUe"
               :fk-target-field-id       nil
               :lib/source               :source/card
               :lib/card-id              3
               :lib/source-column-alias  "Field 4"
               :lib/desired-column-alias "Field 4"}]
             (lib/returned-columns query)))
      (is (= {:lib/type                :metadata/column
              :base-type               :type/Text
              :effective-type          :type/Date
              :coercion-strategy       :Coercion/ISO8601->Date
              :id                      4
              :name                    "Field 4"
              :ident                   "ybTElkkGoYYBAyDRTIiUe"
              :fk-target-field-id      nil
              :display-name            "Field 4"
              :lib/card-id             3
              :lib/source              :source/card
              :lib/source-column-alias "Field 4"
              :lib/source-uuid         "aa0e13af-29b3-4c27-a880-a10c33e55a3e"}
             (lib/metadata
              query
              [:field {:lib/uuid "aa0e13af-29b3-4c27-a880-a10c33e55a3e", :base-type :type/Text} 4]))))))

(deftest ^:parallel ref-to-joined-column-from-previous-stage-test
  (let [query (-> (lib.tu/venues-query)
                  (lib/join (-> (lib/join-clause
                                 (meta/table-metadata :categories)
                                 [(lib/=
                                   (meta/field-metadata :venues :category-id)
                                   (lib/with-join-alias (meta/field-metadata :categories :id) "Categories"))])
                                (lib/with-join-fields [(lib/with-join-alias
                                                        (meta/field-metadata :categories :name)
                                                        "Categories")])))
                  lib/append-stage)
        breakoutables (lib/breakoutable-columns query)
        joined-col (last breakoutables)]
    (is (=? {:lib/type :metadata/column
             :name "NAME"
             :base-type :type/Text
             :semantic-type :type/Name
             :lib/source :source/previous-stage
             :effective-type :type/Text
             :lib/desired-column-alias "Categories__NAME"}
            joined-col))
    (testing "Metadata should not contain inherited join information"
      (is (not-any? :metabase.lib.join/join-alias (lib/returned-columns query))))
    (testing "Reference a joined column from a previous stage w/ desired-column-alias and w/o join-alias"
      (is (=? {:lib/type :mbql.stage/mbql,
               :breakout [[:field
                           {:lib/uuid string?
                            :base-type :type/Text,
                            :effective-type :type/Text
                            :join-alias (symbol "nil #_\"key is not present.\"")}
                           "Categories__NAME"]]}
              (-> (lib/breakout query joined-col) :stages peek))))
    (testing "Binning information is still displayed"
      (is (=? {:name "PRICE",
               :effective-type :type/Integer,
               :semantic-type :type/Category,
               :is-from-join false,
               :long-display-name "Price: Auto binned",
               :display-name "Price",
               :is-from-previous-stage true,
               :is-calculated false,
               :is-implicitly-joinable false}
              (lib/display-info
               query
               (lib/with-binning (m/find-first (comp #{"PRICE"} :name) breakoutables)
                                 (first (lib.binning/numeric-binning-strategies)))))))))

(defn- sorted-fields [fields]
  (sort-by (comp str last) fields))

(defn- fields-of
  ([query] (fields-of query -1))
  ([query stage-number]
   (some-> (lib/fields query stage-number) sorted-fields)))

(deftest ^:parallel populate-fields-for-stage-test
  (testing "simple table query"
    (is (=? [[:field {} (meta/id :orders :id)]
             [:field {} (meta/id :orders :subtotal)]
             [:field {} (meta/id :orders :total)]
             [:field {} (meta/id :orders :tax)]
             [:field {} (meta/id :orders :discount)]
             [:field {} (meta/id :orders :quantity)]
             [:field {} (meta/id :orders :created-at)]
             [:field {} (meta/id :orders :product-id)]
             [:field {} (meta/id :orders :user-id)]]
            (-> (lib/query meta/metadata-provider (meta/table-metadata :orders))
                (#'lib.field/populate-fields-for-stage -1)
                fields-of))))
  (testing "aggregated"
    (let [query (-> (lib/query meta/metadata-provider (meta/table-metadata :orders))
                    (lib/aggregate -1 (lib/count)))]
      (is (=? [[:aggregation {} (-> query lib/aggregations first lib.options/uuid)]]
              (-> query
                  (#'lib.field/populate-fields-for-stage -1)
                  fields-of)))))
  (testing "aggregated with breakout"
    (let [query        (-> (lib/query meta/metadata-provider (meta/table-metadata :orders))
                           (lib/aggregate -1 (lib/count)))
          breakoutable (lib/breakoutable-columns query -1)
          created-at   (first (filter #(= (:name %) "CREATED_AT") breakoutable))
          query        (lib/breakout query -1 (lib/with-temporal-bucket created-at :month))]
      (is (=? (sorted-fields [[:field {:temporal-unit :month} (meta/id :orders :created-at)]
                              [:aggregation {} (-> query lib/aggregations first lib.options/uuid)]])
              (-> query
                  (#'lib.field/populate-fields-for-stage -1)
                  fields-of)))))

  (testing "explicit join fields are *not* included"
    (let [query  (as-> (meta/table-metadata :orders) <>
                   (lib/query meta/metadata-provider <>)
                   (lib/join <> -1 (->> (meta/table-metadata :people)
                                        (lib/suggested-join-conditions <> -1)
                                        (lib/join-clause (meta/table-metadata :people)))))
          fields [[:field {} (meta/id :orders :id)]
                  [:field {} (meta/id :orders :subtotal)]
                  [:field {} (meta/id :orders :total)]
                  [:field {} (meta/id :orders :tax)]
                  [:field {} (meta/id :orders :discount)]
                  [:field {} (meta/id :orders :quantity)]
                  [:field {} (meta/id :orders :created-at)]
                  [:field {} (meta/id :orders :product-id)]
                  [:field {} (meta/id :orders :user-id)]]]
      (testing "when set to :all"
        (is (=? fields
                (-> query
                    (#'lib.field/populate-fields-for-stage -1)
                    fields-of))))
      (testing "when given as a list"
        (is (=? fields
                (let [returned (lib/returned-columns query -1 (first (lib/joins query -1)))]
                  (-> query
                      (lib.util/update-query-stage -1 update-in [:joins 0] lib/with-join-fields (take 3 returned))
                      (#'lib.field/populate-fields-for-stage -1)
                      fields-of)))))))

  (testing "sourced from another card"
    (let [query   (lib.tu/query-with-source-card)]
      (testing "starts with no :fields"
        (is (nil? (-> query (lib.util/query-stage -1) :fields))))
      (testing "populates correctly"
        (is (=? [[:field {} "USER_ID"]
                 [:field {} "count"]]
                (-> query
                    (#'lib.field/populate-fields-for-stage -1)
                    fields-of)))))))

(deftest ^:parallel add-field-tests
  (testing "simple table query"
    (let [query       (lib/query meta/metadata-provider (meta/table-metadata :orders))
          fieldable   (lib/fieldable-columns query -1)
          own-columns (filter #(= (:lib/source %) :source/table-defaults) fieldable)
          created-at  (first (filter #(= (:name %) "CREATED_AT") own-columns))
          subset      (map lib/ref (take 4 own-columns))
          field-query (lib/with-fields query -1 subset)]
      ;; sanity check that the query is constructed properly.
      (is (=? (sorted-fields subset)
              (fields-of field-query)))
      (let [subset-ids (set (map last subset))]
        (is (not (subset-ids (:id created-at)))))
      (testing "does nothing with implicit :all"
        (is (nil? (-> query
                      (lib/add-field -1 created-at)
                      (lib/fields -1)))))
      (testing "adds the column to the :fields list if missing"
        (is (=? (sorted-fields (conj subset [:field {} (:id created-at)]))
                (-> field-query
                    (lib/add-field -1 created-at)
                    fields-of))))
      (testing "doesn't duplicate the column if it already exists"
        (let [extended-subset (conj subset (lib/ref created-at))]
          (is (=? (sorted-fields extended-subset)
                  (-> field-query
                      (lib/with-fields -1 extended-subset)
                      (lib/add-field -1 created-at)
                      fields-of))))))))

(deftest ^:parallel add-field-expressions-test
  (testing "custom expressions are ignored"
    (let [query       (-> (lib/query meta/metadata-provider (meta/table-metadata :orders))
                          (lib/expression "custom" (lib/* 3 2)))
          expr-column (->> (lib/returned-columns query)
                           (remove :id)
                           first)
          own-columns (filter #(= (:lib/source %) :source/table-defaults)
                              (lib/fieldable-columns query -1))
          subset      (map lib/ref (take 4 own-columns))
          field-query (lib/with-fields query -1 subset)]
      (testing "with missing :fields list"
        (is (=? query
                (lib/add-field query -1 expr-column))))
      (testing "with explicit :fields list"
        (is (=? field-query
                (lib/add-field field-query -1 expr-column)))))))

(deftest ^:parallel add-field-join-test
  (testing "single join"
    (let [query  (as-> (meta/table-metadata :orders) <>
                   (lib/query meta/metadata-provider <>)
                   (lib/join <> -1 (->> (meta/table-metadata :people)
                                        (lib/suggested-join-conditions <> -1)
                                        (lib/join-clause (meta/table-metadata :people)))))
          all-columns   (lib/returned-columns query)
          table-columns (lib/fieldable-columns query -1)
          join-columns  (filter #(= (:lib/source %) :source/joins) all-columns)]
      ;; Orders has 9 columns, People has 13, for 22 total.
      (is (= 22 (count all-columns)))
      (is (= 9  (count table-columns)))
      (is (= 13 (count join-columns)))
      (testing "adding an already included field from the main table does nothing"
        (is (= query (lib/add-field query -1 (nth table-columns 6)))))
      (testing "adding an already included field from an :all join does nothing"
        (is (= query (lib/add-field query -1 (nth join-columns 6)))))
      (testing "top-level :fields with only some included"
        (let [field-query (->> table-columns
                               (take 4)
                               (lib/with-fields query -1))]
          (testing "returns those plus all the joined fields"
            (is (=? (->> (concat (take 4 table-columns)
                                 join-columns)
                         (map lib/ref)
                         (map #(lib.options/update-options % dissoc :lib/uuid))
                         sorted-fields)
                    (->> (lib/returned-columns field-query)
                         (map lib/ref)
                         sorted-fields))))
          (testing "properly adds a top-level field"
            (is (=? (->> (concat (take 4 table-columns)
                                 join-columns
                                 [(nth table-columns 6)])
                         (map lib/ref)
                         (map #(lib.options/update-options % dissoc :lib/uuid))
                         sorted-fields)
                    (->> (lib/add-field field-query -1 (nth table-columns 6))
                         lib/returned-columns
                         (map lib/ref)
                         sorted-fields))))))

      (testing "join :fields list"
        (let [join-fields-query (lib.util/update-query-stage
                                 query -1
                                 update-in [:joins 0]
                                 lib/with-join-fields (map lib/ref (take 4 join-columns)))]
          (testing "returns those plus all the main table fields"
            (is (=? (->> (concat table-columns
                                 (take 4 join-columns))
                         (map lib/ref)
                         (map #(lib.options/update-options % dissoc :lib/uuid))
                         sorted-fields)
                    (->> (lib/returned-columns join-fields-query)
                         (map lib/ref)
                         sorted-fields))))
          (testing "properly adds a join field"
            (is (=? (->> (concat table-columns
                                 (take 4 join-columns)
                                 [(nth join-columns 6)])
                         (map lib/ref)
                         (map #(lib.options/update-options % dissoc :lib/uuid))
                         sorted-fields)
                    (->> (lib/add-field join-fields-query -1 (nth join-columns 6))
                         lib/returned-columns
                         (map lib/ref)
                         sorted-fields))))
          (testing "does nothing if the join field is already selected"
            (is (=? join-fields-query
                    (lib/add-field join-fields-query -1 (nth join-columns 3))))))))))

(deftest ^:parallel add-field-implicit-join-test
  (testing "adding implicit join fields"
    (let [query            (lib/query meta/metadata-provider (meta/table-metadata :orders))
          viz-columns      (lib/visible-columns query)
          table-columns    (lib/fieldable-columns query -1)
          implicit-columns (filter #(= (:lib/source %) :source/implicitly-joinable) viz-columns)]
      (is (= (map #(dissoc % :selected?) table-columns)
             (lib/returned-columns query)))
      (testing "with no :fields set"
        (testing "populates the table's fields plus the implicitly joined field"
          (is (=? (->> (concat table-columns
                               [(nth implicit-columns 6)])
                       (map lib/ref)
                       (map #(lib.options/update-options % dissoc :lib/uuid))
                       sorted-fields)
                  (-> query
                      (lib/add-field -1 (nth implicit-columns 6))
                      fields-of)))))

      (testing "with explicit :fields list"
        (let [field-query (->> table-columns
                               (take 4)
                               (map lib/ref)
                               (lib/with-fields query -1))]
          (testing "properly adds the implicitly joined field"
            (is (=? (->> (concat (take 4 table-columns)
                                 [(nth implicit-columns 6)])
                         (map lib/ref)
                         (map #(lib.options/update-options % dissoc :lib/uuid))
                         sorted-fields)
                    (-> field-query
                        (lib/add-field -1 (nth implicit-columns 6))
                        fields-of))))
          (testing "does nothing if this field is already selected"
            (let [implied-query (lib/add-field field-query -1 (nth implicit-columns 6))]
              (is (=? implied-query
                      (lib/add-field implied-query -1 (nth implicit-columns 6)))))))))))

(deftest ^:parallel add-field-multiple-breakouts-test
  (testing "multiple breakouts of the same column in the previous stage"
    (let [query   (-> (lib/query meta/metadata-provider (meta/table-metadata :orders))
                      (lib/aggregate (lib/count))
                      (lib/breakout (lib/with-temporal-bucket (meta/field-metadata :orders :created-at) :year))
                      (lib/breakout (lib/with-temporal-bucket (meta/field-metadata :orders :created-at) :month))
                      (lib/append-stage))
          columns (lib/fieldable-columns query)]
      (testing "removing the column coming from the first breakout"
        (is (=? [[:field {} "CREATED_AT_2"] [:field {} "count"]]
                (-> query
                    (lib/remove-field 1 (first columns))
                    fields-of))))
      (testing "removing the column coming from the second breakout"
        (is (=? [[:field {} "CREATED_AT"] [:field {} "count"]]
                (-> query
                    (lib/remove-field 1 (second columns))
                    fields-of))))
      (testing "removing and adding back the column from the first breakout"
        (is (nil? (-> query
                      (lib/remove-field 1 (first columns))
                      (lib/add-field 1 (first columns))
                      fields-of))))
      (testing "removing and adding back the column from the second breakout"
        (is (nil? (-> query
                      (lib/remove-field 1 (second columns))
                      (lib/add-field 1 (second columns))
                      fields-of)))))))

(defn- clean-ref [column]
  (-> column
      lib/ref
      (lib.options/update-options dissoc :lib/uuid)))

(deftest ^:parallel remove-field-tests
  (testing "simple table query"
    (let [query       (lib/query meta/metadata-provider (meta/table-metadata :orders))
          fieldable   (lib/fieldable-columns query -1)
          own-columns (filter #(= (:lib/source %) :source/table-defaults) fieldable)
          id          (first (filter #(= (:name %) "ID") own-columns))
          created-at  (first (filter #(= (:name %) "CREATED_AT") own-columns))
          subset      (->> own-columns
                           (take 4)
                           (map lib/ref))
          field-query (lib/with-fields query -1 subset)]
      (testing "populates :fields if missing, and removes the field"
        (is (=? (->> own-columns
                     (remove #(= (:id %) (:id created-at)))
                     (map lib/ref)
                     (map #(lib.options/update-options % dissoc :lib/uuid))
                     sorted-fields)
                (-> query
                    (lib/remove-field -1 created-at)
                    fields-of))))
      ;; sanity check that the query is constructed properly.
      (is (=? (sorted-fields subset)
              (fields-of field-query)))
      (testing "removes the column from the :fields list if present"
        (is (=? (->> subset
                     (remove (comp #{(meta/id :orders :id)} last))
                     (map #(lib.options/update-options % assoc :lib/uuid string?))
                     sorted-fields)
                (-> field-query
                    (lib/remove-field -1 id)
                    fields-of))))
      (testing "does nothing if the column is already not selected"
        (is (=? (->> subset
                     (map #(lib.options/update-options % assoc :lib/uuid string?))
                     sorted-fields)
                (-> field-query
                    (lib/remove-field -1 created-at)
                    fields-of)))))))

(deftest ^:parallel remove-field-tests-2
  (testing "single join"
    (let [query  (as-> (meta/table-metadata :orders) <>
                   (lib/query meta/metadata-provider <>)
                   (lib/join <> -1 (->> (meta/table-metadata :people)
                                        (lib/suggested-join-conditions <> -1)
                                        (lib/join-clause (meta/table-metadata :people)))))
          all-columns   (lib/returned-columns query)
          table-columns (lib/fieldable-columns query -1)
          join-columns  (filter #(= (:lib/source %) :source/joins) all-columns)]
      ;; Orders has 9 columns, People has 13, for 22 total.
      (is (= 22 (count all-columns)))
      (is (= 9  (count table-columns)))
      (is (= 13 (count join-columns)))
      (testing "top-level :fields with only some included"
        (let [field-query (->> table-columns
                               (take 4)
                               (lib/with-fields query -1))]
          (testing "properly removes a top-level field"
            (is (=? (->> (concat (rest (take 4 table-columns))
                                 join-columns)
                         (map lib/ref)
                         (map #(lib.options/update-options % dissoc :lib/uuid))
                         sorted-fields)
                    (->> (lib/remove-field field-query -1 (first table-columns))
                         lib/returned-columns
                         (map lib/ref)
                         sorted-fields))))
          (testing "does nothing if field not listed"
            (is (=? field-query
                    (lib/remove-field field-query -1 (nth table-columns 6)))))))

      (testing "with :fields :all"
        (let [created-at (first (filter (comp #{"CREATED_AT"} :name) join-columns))]
          (testing "fills in the :fields list and removes the field"
            (is (=? (->> (concat table-columns
                                 join-columns)
                         (remove (comp #{(meta/id :people :created-at)} :id))
                         (map lib/ref)
                         (map #(lib.options/update-options % dissoc :lib/uuid))
                         sorted-fields)
                    (->> (lib/remove-field query -1 created-at)
                         lib/returned-columns
                         (map lib/ref)
                         sorted-fields))))))

      (testing "with :fields list"
        (let [join-fields-query (lib.util/update-query-stage
                                 query -1
                                 update-in [:joins 0]
                                 lib/with-join-fields (map lib/ref (take 4 join-columns)))]
          (testing ":all fills in the :fields list and removes the field"
            (is (=? (->> (concat table-columns
                                 (rest (take 4 join-columns)))
                         (map lib/ref)
                         (map #(lib.options/update-options % dissoc :lib/uuid))
                         sorted-fields)
                    (->> (lib/remove-field join-fields-query -1 (first join-columns))
                         lib/returned-columns
                         (map lib/ref)
                         sorted-fields))))
          (testing "does nothing if the join field is already selected"
            (is (=? join-fields-query
                    (lib/remove-field join-fields-query -1 (nth join-columns 6)))))))))

  (testing "removing implicit join fields"
    (let [query            (lib/query meta/metadata-provider (meta/table-metadata :orders))
          viz-columns      (lib/visible-columns query)
          table-columns    (lib/fieldable-columns query -1)
          implicit-columns (filter #(= (:lib/source %) :source/implicitly-joinable) viz-columns)
          implied-query    (-> query
                               (lib/add-field -1 (first implicit-columns))
                               (lib/add-field -1 (second implicit-columns)))
          table-fields     (map clean-ref table-columns)
          implied1         (clean-ref (first implicit-columns))
          implied2         (clean-ref (second implicit-columns))]
      (is (= (map #(dissoc % :selected?) table-columns)
             (lib/returned-columns query)))

      (testing "attaching implicitly joined fields should alter the query"
        (is (not= query implied-query))
        (is (nil? (lib.equality/find-matching-ref (first implicit-columns)
                                                  (map lib/ref (lib/returned-columns query))))))

      (testing "with no :fields set does nothing"
        (is (=? query
                (lib/remove-field query -1 (first implicit-columns))))
        (is (=? query
                (lib/remove-field query -1 (second implicit-columns)))))

      (testing "with explicit :fields list"
        (is (=? (sorted-fields (conj table-fields implied2))
                (-> implied-query
                    (lib/remove-field -1 (first implicit-columns))
                    fields-of)))
        (is (=? (sorted-fields (conj table-fields implied1))
                (-> implied-query
                    (lib/remove-field -1 (second implicit-columns))
                    fields-of))))
      (testing "drops the :fields clause when it becomes the defaults"
        (is (= query
               (-> implied-query
                   (lib/remove-field -1 (first implicit-columns))
                   (lib/remove-field -1 (second implicit-columns)))))
        (is (= query
               (-> implied-query
                   (lib/remove-field -1 (second implicit-columns))
                   (lib/remove-field -1 (first implicit-columns)))))))))

(deftest ^:parallel remove-field-tests-3
  (testing "single join"
    (let [query  (as-> (meta/table-metadata :orders) <>
                   (lib/query meta/metadata-provider <>)
                   (lib/join <> -1 (->> (meta/table-metadata :people)
                                        (lib/suggested-join-conditions <> -1)
                                        (lib/join-clause (meta/table-metadata :people)))))
          all-columns   (lib/returned-columns query)
          table-columns (lib/fieldable-columns query -1)
          join-columns  (filter #(= (:lib/source %) :source/joins) all-columns)]
      ;; Orders has 9 columns, People has 13, for 22 total.
      (is (= 22 (count all-columns)))
      (is (= 9  (count table-columns)))
      (is (= 13 (count join-columns)))
      (testing "top-level :fields with only some included"
        (let [field-query (->> table-columns
                               (take 4)
                               (lib/with-fields query -1))]
          (testing "properly removes a top-level field"
            (is (=? (->> (concat (rest (take 4 table-columns))
                                 join-columns)
                         (map lib/ref)
                         (map #(lib.options/update-options % dissoc :lib/uuid))
                         sorted-fields)
                    (->> (lib/remove-field field-query -1 (first table-columns))
                         lib/returned-columns
                         (map lib/ref)
                         sorted-fields))))
          (testing "does nothing if field not listed"
            (is (=? field-query
                    (lib/remove-field field-query -1 (nth table-columns 6)))))))

      (testing "with :fields :all"
        (let [created-at (first (filter (comp #{"CREATED_AT"} :name) join-columns))]
          (testing "fills in the :fields list and removes the field"
            (is (=? (->> (concat table-columns
                                 join-columns)
                         (remove (comp #{(meta/id :people :created-at)} :id))
                         (map lib/ref)
                         (map #(lib.options/update-options % dissoc :lib/uuid))
                         sorted-fields)
                    (->> (lib/remove-field query -1 created-at)
                         lib/returned-columns
                         (map lib/ref)
                         sorted-fields))))))

      (testing "with :fields list"
        (let [join-fields-query (lib.util/update-query-stage
                                 query -1
                                 update-in [:joins 0]
                                 lib/with-join-fields (map lib/ref (take 4 join-columns)))]
          (testing ":all fills in the :fields list and removes the field"
            (is (=? (->> (concat table-columns
                                 (rest (take 4 join-columns)))
                         (map lib/ref)
                         (map #(lib.options/update-options % dissoc :lib/uuid))
                         sorted-fields)
                    (->> (lib/remove-field join-fields-query -1 (first join-columns))
                         lib/returned-columns
                         (map lib/ref)
                         sorted-fields))))
          (testing "does nothing if the join field is already selected"
            (is (=? join-fields-query
                    (lib/remove-field join-fields-query -1 (nth join-columns 6))))))))))

(deftest ^:parallel remove-field-tests-4
  (testing "removing implicit join fields"
    (let [query            (lib/query meta/metadata-provider (meta/table-metadata :orders))
          viz-columns      (lib/visible-columns query)
          table-columns    (lib/fieldable-columns query -1)
          implicit-columns (filter #(= (:lib/source %) :source/implicitly-joinable) viz-columns)
          implied-query    (-> query
                               (lib/add-field -1 (first implicit-columns))
                               (lib/add-field -1 (second implicit-columns)))
          table-fields     (map clean-ref table-columns)
          implied1         (clean-ref (first implicit-columns))
          implied2         (clean-ref (second implicit-columns))]
      (is (= (map #(dissoc % :selected?) table-columns)
             (lib/returned-columns query)))

      (testing "attaching implicitly joined fields should alter the query"
        (is (not= query implied-query))
        (is (nil? (lib.equality/find-matching-ref (first implicit-columns)
                                                  (map lib/ref (lib/returned-columns query))))))

      (testing "with no :fields set does nothing"
        (is (=? query
                (lib/remove-field query -1 (first implicit-columns))))
        (is (=? query
                (lib/remove-field query -1 (second implicit-columns)))))

      (testing "with explicit :fields list"
        (is (=? (sorted-fields (conj table-fields implied2))
                (-> implied-query
                    (lib/remove-field -1 (first implicit-columns))
                    fields-of)))
        (is (=? (sorted-fields (conj table-fields implied1))
                (-> implied-query
                    (lib/remove-field -1 (second implicit-columns))
                    fields-of))))
      (testing "drops the :fields clause when it becomes the defaults"
        (is (= query
               (-> implied-query
                   (lib/remove-field -1 (first implicit-columns))
                   (lib/remove-field -1 (second implicit-columns)))))
        (is (= query
               (-> implied-query
                   (lib/remove-field -1 (second implicit-columns))
                   (lib/remove-field -1 (first implicit-columns)))))))))

(deftest ^:parallel add-remove-fields-source-card-test
  (testing "query with a source card"
    (let [query   (lib.tu/query-with-source-card)
          columns (lib/visible-columns query)]
      (testing "allows removing each of the fields"
        (is (=? [[:field {} "USER_ID"]]
                (-> query
                    (lib/remove-field -1 (second columns))
                    fields-of)))
        (is (=? [[:field {} "count"]]
                (-> query
                    (lib/remove-field -1 (first columns))
                    fields-of))))
      (testing "allows adding back the removed field"
        (is (nil? (-> query
                      (lib/remove-field -1 (second columns))
                      (lib/add-field    -1 (second columns))
                      fields-of)))
        (is (nil? (-> query
                      (lib/remove-field -1 (first columns))
                      (lib/add-field    -1 (first columns))
                      fields-of)))))))

(deftest ^:parallel add-remove-fields-multi-stage-test
  (testing "multi-stage query"
    ;; Our query takes is monthly sales subtotals, with a blank second stage for starters.
    (let [query  (-> (lib/query meta/metadata-provider (meta/table-metadata :orders))
                     (lib/aggregate -1 (lib/sum (meta/field-metadata :orders :subtotal)))
                     (lib/breakout -1 (lib/with-temporal-bucket (meta/field-metadata :orders :created-at) :month))
                     lib/append-stage)
          stage1 (lib.util/query-stage query 1)
          [created-at sum] (lib/visible-columns query 1 stage1)]
      (testing "populating :fields"
        (is (nil? (:fields stage1)))
        (is (=? [[:field {} "CREATED_AT"]
                 [:field {} "sum"]]
                (-> query
                    (#'lib.field/populate-fields-for-stage 1)
                    fields-of))))

      (testing "removing each field"
        (is (=? [[:field {} "CREATED_AT"]]
                (-> query
                    (lib/remove-field 1 sum)
                    fields-of)))
        (is (=? [[:field {} "sum"]]
                (-> query
                    (lib/remove-field 1 created-at)
                    fields-of))))

      (testing "removing and adding each field"
        (is (nil? (-> query
                      (lib/remove-field 1 sum)
                      (lib/add-field    1 sum)
                      fields-of)))
        (is (nil? (-> query
                      (lib/remove-field 1 created-at)
                      (lib/add-field    1 created-at)
                      fields-of)))))))

(deftest ^:parallel add-remove-fields-native-query-test
  (testing "native query"
    (let [native-query   (lib.tu/native-query)
          native-columns (lib/visible-columns native-query)]
      (testing "throws when editing fields directly"
        (is (thrown-with-msg? #?(:cljs :default :clj Exception) #"Fields cannot be adjusted on native queries"
                              (lib/add-field native-query -1 (first native-columns))))
        (is (thrown-with-msg? #?(:cljs :default :clj Exception) #"Fields cannot be adjusted on native queries"
                              (lib/remove-field native-query -1 (first native-columns)))))
      (testing "with MBQL stage"
        (let [query   (lib/append-stage native-query)
              stage1  (lib.util/query-stage query 1)
              columns (lib/visible-columns query 1 stage1)]
          (testing "removing each field"
            (is (=? [[:field {} "sum"]]
                    (-> query
                        (lib/remove-field 1 (first columns))
                        fields-of)))
            (is (=? [[:field {} "abc"]]
                    (-> query
                        (lib/remove-field 1 (second columns))
                        fields-of))))

          (testing "removing and adding each field"
            (is (nil? (-> query
                          (lib/remove-field 1 (first columns))
                          (lib/add-field    1 (first columns))
                          fields-of)))
            (is (nil? (-> query
                          (lib/remove-field 1 (second columns))
                          (lib/add-field    1 (second columns))
                          fields-of)))))))))

(deftest ^:parallel add-remove-fields-aggregation-breakout-test
  (testing "aggregations and breakouts"
    (let [query      (-> (lib/query meta/metadata-provider (meta/table-metadata :orders))
                         (lib/aggregate -1 (lib/sum (meta/field-metadata :orders :subtotal)))
                         (lib/breakout -1 (lib/with-temporal-bucket (meta/field-metadata :orders :created-at) :month)))
          columns    (lib/returned-columns query)]
      (testing "removing each field"
        (is (thrown-with-msg? #?(:cljs :default :clj Exception) #"Only source columns"
                              (lib/remove-field query -1 (first columns))))
        (is (thrown-with-msg? #?(:cljs :default :clj Exception) #"Only source columns"
                              (lib/remove-field query -1 (second columns))))))))

(deftest ^:parallel find-visible-column-for-ref-test
  (testing "precise references"
    (doseq [query-var [#'lib.tu/query-with-expression
                       #'lib.tu/query-with-join-with-explicit-fields
                       #'lib.tu/query-with-source-card]
            :let [query (@query-var)]
            col (lib/visible-columns query)
            :let [col-ref (lib/ref col)]]
      (testing (str "ref " col-ref " of " (symbol query-var))
        (is (= (dissoc col :lib/source-uuid)
               (dissoc (lib/find-visible-column-for-ref query col-ref) :lib/source-uuid))))))
  (testing "reference by ID instead of name"
    (let [query (lib.tu/query-with-source-card)
          col-ref [:field
                   {:lib/uuid "ae24a9b0-cbb5-40b6-bace-c8a5ac6a7e42"
                    :base-type :type/Integer
                    :effective-type :type/Integer}
                   (meta/id :checkins :user-id)]]
      (is (=? {:lib/type :metadata/column
               :base-type :type/Integer
               :semantic-type :type/FK
               :name "USER_ID"
               :lib/card-id 1
               :lib/source :source/card
               :lib/source-column-alias "USER_ID"
               :effective-type :type/Integer
               :id (meta/id :checkins :user-id)
               :lib/desired-column-alias "USER_ID"
               :display-name "User ID"}
              (lib/find-visible-column-for-ref query col-ref))))))

(deftest ^:parallel self-join-ambiguity-test
  (testing "Even when doing a tree-like self join, fields are matched correctly"
    (let [base     (-> (lib/query meta/metadata-provider (meta/table-metadata :orders))
                       (lib/with-fields [(lib/ref (meta/field-metadata :orders :id))
                                         (lib/ref (meta/field-metadata :orders :tax))]))
          join     (-> (lib/join-clause (meta/table-metadata :orders)
                                        [(lib/=
                                          (lib/ref (meta/field-metadata :orders :user-id))
                                          (-> (meta/field-metadata :orders :id)
                                              lib/ref
                                              (lib.options/update-options assoc :join-alias "Orders")))])
                       (lib/with-join-alias "Orders")
                       (lib/with-join-fields [(lib/ref (meta/field-metadata :orders :id))
                                              (lib/ref (meta/field-metadata :orders :tax))]))
          query    (lib/join base join)
          exp-src-id   {:lib/type      :metadata/column
                        :name          "ID"
                        :semantic-type :type/PK
                        :table-id      (meta/id :orders)
                        :id            (meta/id :orders :id)
                        :lib/source    :source/fields
                        :lib/desired-column-alias "ID"
                        :display-name  "ID"}
          exp-src-tax  {:lib/type      :metadata/column
                        :name          "TAX"
                        :semantic-type nil
                        :table-id      (meta/id :orders)
                        :id            (meta/id :orders :tax)
                        :lib/source    :source/fields
                        :lib/desired-column-alias "TAX"
                        :display-name  "Tax"}
          exp-join-id  {:lib/type      :metadata/column
                        :name          "ID"
                        :semantic-type :type/PK
                        :table-id      (meta/id :orders)
                        :id            (meta/id :orders :id)
                        :lib/source    :source/joins
                        :lib/desired-column-alias "Orders__ID"
                        :metabase.lib.join/join-alias "Orders"
                        :display-name  "ID"}
          exp-join-tax {:lib/type      :metadata/column
                        :name          "TAX"
                        :semantic-type nil
                        :table-id      (meta/id :orders)
                        :id            (meta/id :orders :tax)
                        :lib/source    :source/joins
                        :lib/desired-column-alias "Orders__TAX"
                        :metabase.lib.join/join-alias "Orders"
                        :display-name  "Tax"}
          columns      (lib.metadata.calculation/returned-columns query)]
      (is (=? [exp-src-id exp-src-tax exp-join-id exp-join-tax]
              (lib.metadata.calculation/returned-columns query)))

      (doseq [[label column-alias] [["original ID column"  "ID"]
                                    ["original TAX column" "TAX"]
                                    ["joined ID column"    "Orders__ID"]
                                    ["joined TAX column"   "Orders__TAX"]]]
        (testing (str "when hiding the " label)
          (let [col-pred     #(= (:lib/desired-column-alias %) column-alias)
                to-hide      (first (filter col-pred columns))
                ;_ (prn "to hide" to-hide)
                ;_ (prn "query" query)
                hidden       (lib/remove-field query -1 to-hide)
                ;_ (prn "hidden" hidden)
                exp-shown    [exp-src-id exp-src-tax exp-join-id exp-join-tax]
                exp-hidden   (remove col-pred exp-shown)]
            (is (=? exp-hidden
                    (lib.metadata.calculation/returned-columns hidden)))
            (is (=? (map #(dissoc % :lib/source) exp-hidden)
                    (filter :selected? (lib.equality/mark-selected-columns
                                        (lib.metadata.calculation/visible-columns hidden)
                                        (lib.metadata.calculation/returned-columns hidden)))))

            (testing "and showing it again"
              (let [shown     (lib/add-field query -1 to-hide)]
                (is (=? exp-shown
                        (lib.metadata.calculation/returned-columns shown)))
                (is (=? (map #(dissoc % :lib/source) exp-shown)
                        (filter :selected? (lib.equality/mark-selected-columns
                                            (lib.metadata.calculation/visible-columns shown)
                                            (lib.metadata.calculation/returned-columns shown)))))))))))))

(deftest ^:parallel nested-query-add-remove-fields-test
  (testing "a nested query with a field already excluded"
    (let [provider   (lib.tu/metadata-provider-with-cards-for-queries
                      meta/metadata-provider
                      [(lib/query meta/metadata-provider (meta/table-metadata :venues))])
          base       (lib/query provider (lib.metadata/card provider 1))
          columns    (lib.metadata.calculation/returned-columns base)
          price-pred #(= (:name %) "PRICE")
          no-price   (remove price-pred columns)
          query      (lib/with-fields base no-price)]
      (is (empty? (filter price-pred (lib.metadata.calculation/returned-columns query))))
      (let [vis-price (->> query
                           lib.metadata.calculation/visible-columns
                           (filter price-pred)
                           first)]
        (is (=? {:lib/type    :metadata/column
                 :name        "PRICE"
                 :lib/card-id (get-in base [:stages 0 :source-card])
                 :lib/source  :source/card}
                vis-price))

        (testing "can have that dropped field added back"
          (let [added (lib/add-field query -1 vis-price)]
            (is (=? columns #_(map #(assoc % :lib/source :source/fields) columns)
                    (lib.metadata.calculation/returned-columns added)))
            (testing "and removed again"
              (is (=? (map #(assoc % :lib/source :source/fields) no-price)
                      (-> added
                          (lib/remove-field -1 vis-price)
                          lib.metadata.calculation/returned-columns))))))))))

(deftest ^:parallel nested-aggregation-query-remove-fields-test
  (testing "can remove a breakout field from a nested aggregated query (#18817)"
    (let [provider (lib.tu/metadata-provider-with-cards-for-queries
                    meta/metadata-provider
                    [(-> (lib/query meta/metadata-provider (meta/table-metadata :orders))
                         (lib/aggregate (lib/count))
                         (lib/breakout (meta/field-metadata :orders :user-id))
                         (lib/breakout (lib/with-temporal-bucket (meta/field-metadata :orders :created-at) :month)))])
          base     (lib/query provider (lib.metadata/card provider 1))]
      (is (=? [{:display-name "User ID"}
               {:display-name "Created At: Month"}
               {:display-name "Count"}]
              (lib.metadata.calculation/returned-columns base)))
      (let [col-user-id (->> base
                             lib.metadata.calculation/visible-columns
                             (filter #(= (:name %) "USER_ID"))
                             first)
            query (lib/remove-field base -1 col-user-id)]
        (is (=? {:lib/type    :metadata/column
                 :name        "USER_ID"
                 :lib/card-id (get-in base [:stages 0 :source-card])
                 :lib/source  :source/card}
                col-user-id))
        (is (=? [{:display-name "Created At: Month"}
                 {:display-name "Count"}]
                (lib.metadata.calculation/returned-columns query)))))))

(defn- mark-selected [query]
  (lib.equality/mark-selected-columns query -1
                                      (lib.metadata.calculation/visible-columns query)
                                      (lib.metadata.calculation/returned-columns query)))

(deftest ^:parallel nested-query-join-with-fields-test
  (testing "a nested query which has an explicit join with :fields"
    (let [base           (lib/query meta/metadata-provider (meta/table-metadata :orders))
          join           (-> (lib/join-clause (meta/table-metadata :products))
                             (lib/with-join-alias "Products")
                             (lib/with-join-conditions [(lib/= (lib/ref (meta/field-metadata :orders :product-id))
                                                               (lib/ref (meta/field-metadata :products :id)))])
                             (lib/with-join-fields [(meta/field-metadata :products :category)]))
          provider       (lib.tu/metadata-provider-with-cards-for-queries
                          meta/metadata-provider
                          [(lib/join base -1 join)])
          query          (lib/query provider (lib.metadata/card provider 1))
          order-cols     (for [col (meta/fields :orders)]
                           (-> (meta/field-metadata :orders col)
                               (assoc :lib/source :source/card)
                               (dissoc :id :table-id)))
          join-cols      [(-> (meta/field-metadata :products :category)
                              (assoc :lib/source :source/card
                                     :source-alias "Products")
                              (update :ident lib.metadata.ident/explicitly-joined-ident (:ident join))
                              (dissoc :id :table-id))]
          implicit-cols  (for [col (meta/fields :people)]
                           (-> (meta/field-metadata :people col)
                               (update :ident lib.metadata.ident/implicitly-joined-ident (meta/ident :orders :user-id))
                               (assoc :lib/source :source/implicitly-joinable)))
          sorted         #(sort-by (juxt :name :join-alias :id :table-id) %)]
      (is (=? (sorted (concat order-cols join-cols))
              (sorted (lib.metadata.calculation/returned-columns query))))
      (testing "visible-columns returns implicitly joinable People, but does not return two copies of Product.CATEGORY"
        (is (=? (sorted (concat order-cols join-cols implicit-cols))
                (sorted (lib.metadata.calculation/visible-columns query))))))))

(deftest ^:parallel nested-query-implicit-join-fields-test
  (testing "joining a nested query with another table"
    ;; Use the mock card for :orders, join that with products in the nested query.
    (let [provider  (lib.tu/metadata-provider-with-cards-for-queries
                     meta/metadata-provider
                     [(lib/query meta/metadata-provider (meta/table-metadata :orders))])
          base      (lib/query provider (lib.metadata/card provider 1))
          join      (lib/join-clause (meta/table-metadata :products)
                                     [(lib/= (lib/ref (meta/field-metadata :orders :product-id))
                                             (lib/ref (meta/field-metadata :products :id)))])
          query     (lib/join base -1 join)
          get-state (fn [cols] (first (filter #(= (:id %) (meta/id :people :state)) cols)))
          joined    (->> query
                         lib.metadata.calculation/visible-columns
                         get-state
                         (lib/add-field query -1))]
      (testing "can add an implicit join"
        (is (= (inc (count (lib.metadata.calculation/returned-columns query)))
               (count (lib.metadata.calculation/returned-columns joined)))))

      (testing "correctly marks columns as selected"
        (testing "without the implicit join"
          (is (not (-> query mark-selected get-state :selected?))))
        (testing "with the implicit join"
          (is (=? {:id (meta/id :people :state)
                   :lib/source :source/implicitly-joinable
                   :selected? true}
                  (get-state (mark-selected joined)))))))))

(deftest ^:parallel expression-ref-when-metadata-has-expression-name-test
  (testing (str "column metadata with :expression-name should generate :expression refs regardless of :lib/source. "
                "Prefer :expression-name over :name (#34957)")
    (let [metadata (-> (meta/field-metadata :venues :name)
                       (assoc :lib/source :source/fields
                              :lib/expression-name "Custom Venue Name"))]
      (is (=? [:expression {} "Custom Venue Name"]
              (lib/ref metadata))))))

(deftest ^:parallel resolve-field-metadata-test
  (testing "Make sure fallback name for a Field ref makes sense"
    (mu/disable-enforcement
      (binding [lib.metadata.ident/*enforce-idents-present* false]
        (is (=? {:lib/type        :metadata/column
                 :lib/source-uuid string?
                 :name            "12345"
                 :display-name    "Unknown Field"}
                (lib.metadata.calculation/metadata (lib.tu/venues-query) -1
                                                   [:field {:lib/uuid (str (random-uuid))} 12345])))))))

(deftest ^:parallel field-values-search-info-test
  (testing "type/PK field remapped to a type/Name field within the same table"
    (let [name-field (lib.metadata/field meta/metadata-provider (meta/id :venues :name))
          metadata-provider (lib.tu/merged-mock-metadata-provider
                             meta/metadata-provider
                             {:fields [{:id  (meta/id :venues :id)
                                        :name-field       name-field
                                        :has-field-values :search}
                                       {:id               (meta/id :venues :name)
                                        :semantic-type    :type/Name}]})
          venues-id       (lib.metadata/field metadata-provider (meta/id :venues :id))]
      (testing `lib.metadata/remapped-field
        (is (nil? (lib.metadata/remapped-field metadata-provider venues-id))))
      (testing `lib.field/search-field
        (is (=? {:id   (meta/id :venues :name)
                 :name "NAME"}
                (#'lib.field/search-field metadata-provider venues-id))))
      (is (=? {:field-id         (meta/id :venues :id)
               :search-field-id  (meta/id :venues :name)
               :search-field     {:id (meta/id :venues :name)
                                  :display-name "Name"}
               :has-field-values :search}
              (lib.field/field-values-search-info metadata-provider venues-id)))))
  (testing "type/FK field remapped to a field in another table"
    (let [metadata-provider (-> meta/metadata-provider
                                (lib.tu/merged-mock-metadata-provider {:fields [{:id               (meta/id :venues :name)
                                                                                 :semantic-type    :type/FK
                                                                                 :has-field-values nil}]})
                                (lib.tu/remap-metadata-provider (meta/id :venues :name) (meta/id :categories :name)))
          venues-name       (lib.metadata/field metadata-provider (meta/id :venues :name))]
      (testing `lib.types.isa/searchable?
        (is (lib.types.isa/searchable? venues-name)))
      (testing `lib.metadata/remapped-field
        (let [remapped-field (lib.metadata/remapped-field metadata-provider venues-name)]
          (is (=? {:id   (meta/id :categories :name)
                   :name "NAME"}
                  (lib.metadata/remapped-field metadata-provider venues-name)))
          (is (lib.types.isa/searchable? remapped-field))))
      (testing `lib.field/search-field
        (is (=? {:id   (meta/id :categories :name)
                 :name "NAME"}
                (#'lib.field/search-field metadata-provider venues-name))))
      (is (=? {:field-id         (meta/id :venues :name)
               :search-field-id  (meta/id :categories :name)
               :search-field     {:id (meta/id :categories :name)
                                  :display-name "Name"}
               :has-field-values :search}
              (lib.field/field-values-search-info metadata-provider venues-name))))))

(deftest ^:parallel field-values-search-info-pks-test
  (testing "Don't return anything for PKs"
    (let [metadata-provider (-> meta/metadata-provider
                                (lib.tu/merged-mock-metadata-provider {:fields [{:id               (meta/id :venues :id)
                                                                                 :has-field-values :auto-list}]})
                                (lib.tu/remap-metadata-provider (meta/id :venues :id) (meta/id :categories :name)))]
      (is (= {:field-id         (meta/id :venues :id)
              :search-field-id  nil
              :search-field     nil
              :has-field-values :list}
             (lib.field/field-values-search-info
              metadata-provider
              (lib.metadata/field metadata-provider (meta/id :venues :id))))))))

(deftest ^:parallel field-values-search-info-native-test
  (testing "No field-id without custom metadata (#37100)"
    (is (= {:field-id nil
            :search-field-id nil
            :search-field nil
            :has-field-values :none}
           (lib.field/field-values-search-info
            meta/metadata-provider
            (-> (lib.tu/native-query)
                lib/visible-columns
                first))))
    (is (= {:field-id nil
            :search-field-id nil
            :search-field nil
            :has-field-values :none}
           (lib.field/field-values-search-info
            meta/metadata-provider
            (-> (lib.tu/query-with-stage-metadata-from-card
                 meta/metadata-provider
                 (:venues/native (lib.tu/mock-cards)))
                lib/visible-columns
                first))))
    (is (= {:field-id nil
            :search-field-id nil
            :search-field nil
            :has-field-values :none}
           (lib.field/field-values-search-info
            meta/metadata-provider
            (-> (lib.tu/query-with-stage-metadata-from-card
                 meta/metadata-provider
                 (:venues/native (lib.tu/mock-cards)))
                lib/append-stage
                lib/visible-columns
                first))))
    (is (= {:field-id nil
            :search-field-id nil
            :search-field nil
            :has-field-values :none}
           (lib.field/field-values-search-info
            meta/metadata-provider
            (->> (lib.tu/query-with-stage-metadata-from-card
                  meta/metadata-provider
                  (:venues/native (lib.tu/mock-cards)))
                 lib/append-stage
                 lib/visible-columns
                 (m/find-first (comp #{"NAME"} :name)))))))
  (testing "field-id with custom metadata (#37100)"
    (is (=? {:field-id 1
             :search-field-id 1
             :search-field {:id 1
                            :display-name "Search"}
             :has-field-values :search}
            (lib.field/field-values-search-info
             meta/metadata-provider
             (-> (update-in (lib.tu/native-query) [:stages 0 :lib/stage-metadata :columns] conj
                            {:lib/type :metadata/column
                             :id 1
                             :name "search"
                             :ident "pu_Pfm-Oe2cnFTsRgmYM3"
                             :display-name "Search"
                             :base-type :type/Text})
                 lib/visible-columns
                 last))))
    (is (= {:field-id 1
            :search-field-id nil
            :search-field nil
            :has-field-values :none}
           (lib.field/field-values-search-info
            meta/metadata-provider
            (-> (update-in (lib.tu/native-query) [:stages 0 :lib/stage-metadata :columns] conj
                           {:lib/type :metadata/column
                            :id 1
                            :name "num"
                            :ident "pu_Pfm-Oe2cnFTsRgmYM3"
                            :display-name "Random number"
                            :base-type :type/Integer})
                lib/visible-columns
                last))))))

(deftest ^:parallel with-temporal-bucket-effective-type-test
  (let [column (meta/field-metadata :orders :created-at)]
    (testing "should restore the original effective-type when removing a temporal-unit"
      (doseq [unit lib.schema.temporal-bucketing/datetime-bucketing-units]
        (is (= (:effective-type column) (-> column
                                            (lib/with-temporal-bucket unit)
                                            (lib/with-temporal-bucket nil)
                                            (:effective-type))))))))

(deftest ^:parallel display-name-of-aggregation-over-joined-field-from-previous-stage-test
  (testing "long display name of an aggregation over a joined field from the previous stage (#50308)"
    (let [base-query        (lib.tu/query-with-join)
          venues-id         (m/find-first #(= (:id %) (meta/id :venues :id))
                                          (lib/visible-columns base-query))
          _                 (is (some? venues-id))
          categories-id     (m/find-first #(= (:id %) (meta/id :categories :id))
                                          (lib/visible-columns base-query))
          _                 (is (some? categories-id))
          query             (-> (lib.tu/query-with-join)
                                (lib/aggregate (lib/max venues-id))
                                (lib/aggregate (lib/max categories-id))
                                lib/append-stage)
          max-venues-id     (first (lib/returned-columns query))
          _                 (is (some? max-venues-id))
          max-categories-id (second (lib/returned-columns query))
          _                 (is (some? max-categories-id))]
      (is (= "Max of ID"
             (lib/display-name query -1 max-venues-id)))
      (is (= "Max of ID"
             (lib/display-name query -1 max-categories-id)))
      (is (= "Max of ID"
             (lib/display-name query -1 max-venues-id :long)))
      (is (= "Max of Cat → ID"
             (lib/display-name query -1 max-categories-id :long))))))
