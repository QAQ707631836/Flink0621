package chapter06;

import bean.AdClickLog;
import bean.CountByProAdWithWindowEnd;
import bean.SimpleAggregateFunction;
import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.api.common.functions.MapFunction;
import org.apache.flink.api.common.state.ListState;
import org.apache.flink.api.common.state.ListStateDescriptor;
import org.apache.flink.api.common.typeinfo.TypeHint;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.api.java.functions.KeySelector;
import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.api.java.tuple.Tuple4;
import org.apache.flink.api.java.tuple.Tuple8;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.streaming.api.TimeCharacteristic;
import org.apache.flink.streaming.api.datastream.KeyedStream;
import org.apache.flink.streaming.api.datastream.SingleOutputStreamOperator;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.functions.KeyedProcessFunction;
import org.apache.flink.streaming.api.functions.windowing.ProcessWindowFunction;
import org.apache.flink.streaming.api.windowing.time.Time;
import org.apache.flink.streaming.api.windowing.windows.TimeWindow;
import org.apache.flink.util.Collector;
import org.apache.flink.util.OutputTag;

import java.io.Serializable;
import java.sql.Blob;
import java.sql.Clob;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * TODO
 *
 * @author cjp
 * @version 1.0
 * @date 2020/12/5 10:31
 */
public class Flink33_HotAdClickAnalysisByUser {
    public static void main(String[] args) throws Exception {
        // 1.创建执行环境
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.setParallelism(1);
        env.setStreamTimeCharacteristic(TimeCharacteristic.EventTime);
        // 2.读取数据
        SingleOutputStreamOperator<AdClickLog> adClickDS = env
                .readTextFile("input/AdClickLog.csv")
                .map(new MapFunction<String, AdClickLog>() {
                    @Override
                    public AdClickLog map(String value) throws Exception {
                        String[] datas = value.split(",");
                        return new AdClickLog(
                                Long.valueOf(datas[0]),
                                Long.valueOf(datas[1]),
                                datas[2],
                                datas[3],
                                Long.valueOf(datas[4])
                        );
                    }
                })
                .assignTimestampsAndWatermarks(
                        WatermarkStrategy
                                .<AdClickLog>forBoundedOutOfOrderness(Duration.ofSeconds(3))
                                .withTimestampAssigner((data, ts) -> data.getTimestamp() * 1000L)
                );
        // 3.处理数据
        // 3.1 按照 统计维度 分组： 用户、广告
        KeyedStream<AdClickLog, Tuple2<Long, Long>> adClickKS = adClickDS.keyBy(new KeySelector<AdClickLog, Tuple2<Long, Long>>() {
            @Override
            public Tuple2<Long, Long> getKey(AdClickLog value) throws Exception {
                return Tuple2.of(value.getUserId(), value.getAdId());
            }
        });
        // 3.2 开窗
        OutputTag<AdClickLog> outputTag = new OutputTag<AdClickLog>("late-late") {
        };
        adClickKS
                .timeWindow(Time.hours(1), Time.minutes(5))
                .allowedLateness(Time.seconds(6))
                .sideOutputLateData(outputTag)
                .aggregate(new SimpleAggregateFunction<AdClickLog>(),
                        new ProcessWindowFunction<Long, Tuple4<Long, Long, Long, Long>, Tuple2<Long, Long>, TimeWindow>() {
                            @Override
                            public void process(Tuple2<Long, Long> stringLongTuple2, Context context, Iterable<Long> elements, Collector<Tuple4<Long, Long, Long, Long>> out) throws Exception {
                                // (key1,key2,count,windowEnd)
                                out.collect(Tuple4.of(stringLongTuple2.f0, stringLongTuple2.f1, elements.iterator().next(), context.window().getEnd()));
                            }
                        })
                .keyBy(r -> r.f3)
                .process(
                        new KeyedProcessFunction<Long, Tuple4<Long, Long, Long, Long>, String>() {
                            ListState<Tuple4<Long, Long, Long, Long>> datas;

                            @Override
                            public void open(Configuration parameters) throws Exception {
                                datas = getRuntimeContext().getListState(new ListStateDescriptor<Tuple4<Long, Long, Long, Long>>("datas",
                                        TypeInformation.of(new TypeHint<Tuple4<Long, Long, Long, Long>>() {
                                        })));

                            }

                            @Override
                            public void processElement(Tuple4<Long, Long, Long, Long> value, Context ctx, Collector<String> out) throws Exception {
                                // 存数据
                                datas.add(value);
                                // 注册定时器
                                ctx.timerService().registerEventTimeTimer(value.f3 + 100L);
                            }

                            @Override
                            public void onTimer(long timestamp, OnTimerContext ctx, Collector<String> out) throws Exception {
                                // 取出数据、排序
                                List<Tuple4<Long, Long, Long, Long>> list = new ArrayList<>();
                                for (Tuple4<Long, Long, Long, Long> data : datas.get()) {
                                    list.add(data);
                                }
                                // 清空状态
                                datas.clear();
                                // 排序
                                list.sort(new Comparator<Tuple4<Long, Long, Long, Long>>() {
                                    @Override
                                    public int compare(Tuple4<Long, Long, Long, Long> o1, Tuple4<Long, Long, Long, Long> o2) {
                                        return (int) (o2.f2 - o1.f2);
                                    }
                                });

                                // 取前N
                                // 取 前 N 个
                                StringBuffer resultStr = new StringBuffer();
                                resultStr.append("==============================================\n");
                                for (int i = 0; i < Math.min(3, list.size()); i++) {
                                    resultStr.append("Top" + (i + 1) + ":" + list.get(i) + "\n");
                                }
                                resultStr.append("=======================================\n\n\n");

                                out.collect(resultStr.toString());
                            }
                        }
                )
                .print();

        env.execute();
    }
}
