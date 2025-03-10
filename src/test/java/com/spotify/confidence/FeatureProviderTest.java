package com.spotify.confidence;

import static dev.openfeature.sdk.ErrorCode.GENERAL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import com.google.protobuf.Struct;
import com.google.protobuf.util.Structs;
import com.google.protobuf.util.Values;
import com.spotify.confidence.flags.resolver.v1.FlagResolverServiceGrpc.FlagResolverServiceImplBase;
import com.spotify.confidence.flags.resolver.v1.ResolveFlagsRequest;
import com.spotify.confidence.flags.resolver.v1.ResolveFlagsResponse;
import com.spotify.confidence.flags.resolver.v1.ResolvedFlag;
import com.spotify.confidence.flags.types.v1.FlagSchema;
import com.spotify.confidence.flags.types.v1.FlagSchema.BoolFlagSchema;
import com.spotify.confidence.flags.types.v1.FlagSchema.DoubleFlagSchema;
import com.spotify.confidence.flags.types.v1.FlagSchema.IntFlagSchema;
import com.spotify.confidence.flags.types.v1.FlagSchema.ListFlagSchema;
import com.spotify.confidence.flags.types.v1.FlagSchema.StringFlagSchema;
import com.spotify.confidence.flags.types.v1.FlagSchema.StructFlagSchema;
import dev.openfeature.sdk.Client;
import dev.openfeature.sdk.ErrorCode;
import dev.openfeature.sdk.EvaluationContext;
import dev.openfeature.sdk.FeatureProvider;
import dev.openfeature.sdk.FlagEvaluationDetails;
import dev.openfeature.sdk.MutableContext;
import dev.openfeature.sdk.MutableStructure;
import dev.openfeature.sdk.OpenFeatureAPI;
import dev.openfeature.sdk.ProviderState;
import dev.openfeature.sdk.Value;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.util.*;
import java.util.function.BiConsumer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

final class FeatureProviderTest {

  private static Server server;
  private static ManagedChannel channel;
  private static final Value DEFAULT_VALUE = new Value("string-default");
  private static final FlagResolverServiceImplBase serviceImpl =
      mock(FlagResolverServiceImplBase.class);
  private static Client client;
  private static OpenFeatureAPI openFeatureAPI;

  private static ResolveFlagsResponse generateSampleResponse(
      List<ValueSchemaHolder> additionalProps) {
    return ResolveFlagsResponse.newBuilder()
        .addResolvedFlags(generateResolvedFlag(additionalProps))
        .build();
  }

  private static final EvaluationContext SAMPLE_CONTEXT_WITHOUT_TARGETING_KEY =
      new MutableContext(Map.of("my-key", new Value(true)));

  private static final EvaluationContext SAMPLE_CONTEXT_2_TARGETING_KEYS =
      new MutableContext(
          "my-targeting-key-1",
          Map.of(
              com.spotify.confidence.ConfidenceFeatureProvider.TARGETING_KEY,
              new Value("my-targeting-key-2")));

  private static final EvaluationContext SAMPLE_CONTEXT =
      new MutableContext("my-targeting-key", Map.of("my-key", new Value(true)));

  static final String serverName = InProcessServerBuilder.generateName();

  @BeforeAll
  static void before() throws IOException {

    server =
        InProcessServerBuilder.forName(serverName)
            .directExecutor()
            .addService(serviceImpl)
            .build()
            .start();
  }

  @BeforeEach
  void beforeEach() {
    channel = InProcessChannelBuilder.forName(serverName).directExecutor().build();

    final FeatureProvider featureProvider = new ConfidenceFeatureProvider("fake-secret", channel);

    openFeatureAPI = OpenFeatureAPI.getInstance();
    openFeatureAPI.setProvider(featureProvider);

    client = openFeatureAPI.getClient();
  }

  @AfterAll
  static void after() {
    channel.shutdownNow();
    server.shutdownNow();
  }

  @Test
  public void nonExistingFlag() {

    mockResolve(
        (request, streamObserver) -> {
          streamObserver.onNext(ResolveFlagsResponse.getDefaultInstance());
          streamObserver.onCompleted();
        });

    final FlagEvaluationDetails<Value> evaluationDetails =
        client.getObjectDetails("not-existing", DEFAULT_VALUE, SAMPLE_CONTEXT);

    assertThat(evaluationDetails.getValue()).isEqualTo(DEFAULT_VALUE);
    assertThat(evaluationDetails.getErrorCode()).isEqualTo(ErrorCode.FLAG_NOT_FOUND);
    assertThat(evaluationDetails.getReason()).isEqualTo("ERROR");
    assertThat(evaluationDetails.getVariant()).isBlank();
    assertThat(evaluationDetails.getErrorMessage())
        .isEqualTo("No active flag 'not-existing' was found");
  }

  @Test
  public void unexpectedFlag() {

    mockResolve(
        (request, streamObserver) -> {
          streamObserver.onNext(
              ResolveFlagsResponse.newBuilder()
                  .addResolvedFlags(
                      ResolvedFlag.newBuilder().setFlag("flags/unexpected-flag").build())
                  .build());
          streamObserver.onCompleted();
        });

    final FlagEvaluationDetails<Value> evaluationDetails =
        client.getObjectDetails("flag", DEFAULT_VALUE, SAMPLE_CONTEXT);

    assertThat(evaluationDetails.getValue()).isEqualTo(DEFAULT_VALUE);
    assertThat(evaluationDetails.getErrorCode()).isEqualTo(ErrorCode.FLAG_NOT_FOUND);
    assertThat(evaluationDetails.getReason()).isEqualTo("ERROR");
    assertThat(evaluationDetails.getVariant()).isBlank();
    assertThat(evaluationDetails.getErrorMessage())
        .isEqualTo("Unexpected flag 'unexpected-flag' from remote");
  }

  @Test
  public void unavailableApi() {

    mockResolve(
        (request, streamObserver) -> streamObserver.onError(Status.UNAVAILABLE.asException()));

    final FlagEvaluationDetails<Value> evaluationDetails =
        client.getObjectDetails("flags/whatever", DEFAULT_VALUE, SAMPLE_CONTEXT);

    assertThat(evaluationDetails.getValue()).isEqualTo(DEFAULT_VALUE);
    assertThat(evaluationDetails.getErrorCode()).isEqualTo(GENERAL);
    assertThat(evaluationDetails.getReason()).isEqualTo("ERROR");
    assertThat(evaluationDetails.getErrorMessage()).isEqualTo("Provider backend is unavailable");
    assertThat(evaluationDetails.getVariant()).isBlank();
  }

  @Test
  public void unauthenticated() {

    mockResolve(
        (request, streamObserver) -> streamObserver.onError(Status.UNAUTHENTICATED.asException()));

    final FlagEvaluationDetails<Value> evaluationDetails =
        client.getObjectDetails("flags/whatever", DEFAULT_VALUE, SAMPLE_CONTEXT);

    assertThat(evaluationDetails.getValue()).isEqualTo(DEFAULT_VALUE);
    assertThat(evaluationDetails.getErrorCode()).isEqualTo(GENERAL);
    assertThat(evaluationDetails.getReason()).isEqualTo("ERROR");
    assertThat(evaluationDetails.getErrorMessage()).isEqualTo("UNAUTHENTICATED");
    assertThat(evaluationDetails.getVariant()).isBlank();
  }

  @Test
  public void lackOfAssignment() {

    mockResolve(
        (request, streamObserver) -> {
          streamObserver.onNext(
              ResolveFlagsResponse.newBuilder()
                  .addResolvedFlags(ResolvedFlag.newBuilder().setFlag("flags/whatever").build())
                  .build());
          streamObserver.onCompleted();
        });

    final FlagEvaluationDetails<Value> evaluationDetails =
        client.getObjectDetails("whatever", DEFAULT_VALUE, SAMPLE_CONTEXT);

    assertThat(evaluationDetails.getValue()).isEqualTo(DEFAULT_VALUE);
    assertThat(evaluationDetails.getErrorCode()).isNull();
    assertThat(evaluationDetails.getErrorMessage()).isBlank();
    assertThat(evaluationDetails.getReason())
        .startsWith("The server returned no assignment for the flag");
    assertThat(evaluationDetails.getVariant()).isBlank();
  }

  @Test
  public void regularResolve() {

    mockResolve(
        (ResolveFlagsRequest, streamObserver) -> {
          assertThat(ResolveFlagsRequest.getFlags(0)).isEqualTo("flags/flag");

          assertThat(ResolveFlagsRequest.getEvaluationContext())
              .isEqualTo(
                  Structs.of(
                      "my-key", Values.of(true), "targeting_key", Values.of("my-targeting-key")));

          streamObserver.onNext(generateSampleResponse(Collections.emptyList()));
          streamObserver.onCompleted();
        });

    final FlagEvaluationDetails<Value> evaluationDetails =
        client.getObjectDetails("flag", DEFAULT_VALUE, SAMPLE_CONTEXT);

    assertThat(evaluationDetails.getErrorCode()).isNull();
    assertThat(evaluationDetails.getVariant()).isEqualTo("flags/flag/variants/var-A");
    assertThat(evaluationDetails.getErrorMessage()).isBlank();
    assertThat(evaluationDetails.getValue())
        .isEqualTo(
            new Value(
                new MutableStructure(
                    Map.of(
                        "prop-A",
                        new Value(false),
                        "prop-B",
                        new Value(
                            new MutableStructure(
                                Map.of("prop-C", new Value("str-val"), "prop-D", new Value(5.3)))),
                        "prop-E",
                        new Value(50),
                        "prop-F",
                        new Value(List.of(new Value("a"), new Value("b"))),
                        "prop-G",
                        new Value(
                            new MutableStructure(
                                Map.of(
                                    "prop-H", new Value(),
                                    "prop-I", new Value())))))));
  }

  @Test
  public void regularResolveWithoutTargetingKey() {

    mockResolve(
        (ResolveFlagsRequest, streamObserver) -> {
          assertThat(ResolveFlagsRequest.getFlags(0)).isEqualTo("flags/flag");

          assertThat(ResolveFlagsRequest.getEvaluationContext())
              .isEqualTo(Structs.of("my-key", Values.of(true)));

          streamObserver.onNext(generateSampleResponse(Collections.emptyList()));
          streamObserver.onCompleted();
        });

    final FlagEvaluationDetails<Value> evaluationDetails =
        client.getObjectDetails("flag", DEFAULT_VALUE, SAMPLE_CONTEXT_WITHOUT_TARGETING_KEY);

    assertThat(evaluationDetails.getErrorCode()).isNull();
    assertThat(evaluationDetails.getVariant()).isEqualTo("flags/flag/variants/var-A");
    assertThat(evaluationDetails.getErrorMessage()).isBlank();
    assertThat(evaluationDetails.getValue())
        .isEqualTo(
            new Value(
                new MutableStructure(
                    Map.of(
                        "prop-A",
                        new Value(false),
                        "prop-B",
                        new Value(
                            new MutableStructure(
                                Map.of("prop-C", new Value("str-val"), "prop-D", new Value(5.3)))),
                        "prop-E",
                        new Value(50),
                        "prop-F",
                        new Value(List.of(new Value("a"), new Value("b"))),
                        "prop-G",
                        new Value(
                            new MutableStructure(
                                Map.of(
                                    "prop-H", new Value(),
                                    "prop-I", new Value())))))));
  }

  @Test
  public void regularResolveWith2TargetingKeyShouldPrioritiseApiOverMap() {

    mockResolve(
        (ResolveFlagsRequest, streamObserver) -> {
          assertThat(ResolveFlagsRequest.getFlags(0)).isEqualTo("flags/flag");

          assertThat(ResolveFlagsRequest.getEvaluationContext())
              .isEqualTo(Structs.of("targeting_key", Values.of("my-targeting-key-1")));

          streamObserver.onNext(generateSampleResponse(Collections.emptyList()));
          streamObserver.onCompleted();
        });

    final FlagEvaluationDetails<Value> evaluationDetails =
        client.getObjectDetails("flag", DEFAULT_VALUE, SAMPLE_CONTEXT_2_TARGETING_KEYS);

    assertThat(evaluationDetails.getErrorCode()).isNull();
    assertThat(evaluationDetails.getVariant()).isEqualTo("flags/flag/variants/var-A");
    assertThat(evaluationDetails.getErrorMessage()).isBlank();
    assertThat(evaluationDetails.getValue())
        .isEqualTo(
            new Value(
                new MutableStructure(
                    Map.of(
                        "prop-A",
                        new Value(false),
                        "prop-B",
                        new Value(
                            new MutableStructure(
                                Map.of("prop-C", new Value("str-val"), "prop-D", new Value(5.3)))),
                        "prop-E",
                        new Value(50),
                        "prop-F",
                        new Value(List.of(new Value("a"), new Value("b"))),
                        "prop-G",
                        new Value(
                            new MutableStructure(
                                Map.of(
                                    "prop-H", new Value(),
                                    "prop-I", new Value())))))));
  }

  @Test
  public void regularResolveWithPath() {

    mockSampleResponse();

    // 1-element path to non-structure value
    FlagEvaluationDetails<Value> evaluationDetails =
        client.getObjectDetails("flag.prop-A", DEFAULT_VALUE, SAMPLE_CONTEXT);
    assertThat(evaluationDetails.getErrorCode()).isNull();
    assertThat(evaluationDetails.getVariant()).isEqualTo("flags/flag/variants/var-A");
    assertThat(evaluationDetails.getErrorMessage()).isBlank();
    assertThat(evaluationDetails.getValue()).isEqualTo(new Value(false));

    // 1-element path to structure
    evaluationDetails = client.getObjectDetails("flag.prop-B", DEFAULT_VALUE, SAMPLE_CONTEXT);
    assertThat(evaluationDetails.getErrorCode()).isNull();
    assertThat(evaluationDetails.getVariant()).isEqualTo("flags/flag/variants/var-A");
    assertThat(evaluationDetails.getErrorMessage()).isBlank();
    assertThat(evaluationDetails.getValue())
        .isEqualTo(
            new Value(
                new MutableStructure(
                    Map.of("prop-C", new Value("str-val"), "prop-D", new Value(5.3)))));

    // 2-element path to non-structure
    evaluationDetails =
        client.getObjectDetails("flag.prop-B.prop-C", DEFAULT_VALUE, SAMPLE_CONTEXT);
    assertThat(evaluationDetails.getErrorCode()).isNull();
    assertThat(evaluationDetails.getVariant()).isEqualTo("flags/flag/variants/var-A");
    assertThat(evaluationDetails.getErrorMessage()).isBlank();
    assertThat(evaluationDetails.getValue()).isEqualTo(new Value("str-val"));

    // 1-element path to null value, returns default
    evaluationDetails =
        client.getObjectDetails("flag.prop-G.prop-H", DEFAULT_VALUE, SAMPLE_CONTEXT);
    assertThat(evaluationDetails.getErrorCode()).isNull();
    assertThat(evaluationDetails.getVariant()).isEqualTo("flags/flag/variants/var-A");
    assertThat(evaluationDetails.getErrorMessage()).isBlank();
    assertThat(evaluationDetails.getValue()).isEqualTo(DEFAULT_VALUE);

    // derive field on non-structure
    evaluationDetails =
        client.getObjectDetails("flag.prop-A.not-exist", DEFAULT_VALUE, SAMPLE_CONTEXT);
    assertThat(evaluationDetails.getErrorCode()).isEqualTo(ErrorCode.TYPE_MISMATCH);
    assertThat(evaluationDetails.getVariant()).isBlank();
    assertThat(evaluationDetails.getReason()).isEqualTo("ERROR");
    assertThat(evaluationDetails.getErrorMessage())
        .isEqualTo(
            String.format(
                "Illegal attempt to derive field 'not-exist' on non-structure value '%s'",
                new Value(false)));
    assertThat(evaluationDetails.getValue()).isEqualTo(DEFAULT_VALUE);

    // non-existing field on structure
    evaluationDetails = client.getObjectDetails("flag.not-exist", DEFAULT_VALUE, SAMPLE_CONTEXT);
    assertThat(evaluationDetails.getErrorCode()).isEqualTo(ErrorCode.TYPE_MISMATCH);
    assertThat(evaluationDetails.getVariant()).isBlank();
    assertThat(evaluationDetails.getReason()).isEqualTo("ERROR");
    assertThat(evaluationDetails.getErrorMessage())
        .isEqualTo(
            String.format(
                "Illegal attempt to derive non-existing field 'not-exist' on structure value '%s'",
                new MutableStructure(
                    Map.of(
                        "prop-A",
                        new Value(false),
                        "prop-B",
                        new Value(
                            new MutableStructure(
                                Map.of("prop-C", new Value("str-val"), "prop-D", new Value(5.3)))),
                        "prop-E",
                        new Value(50),
                        "prop-F",
                        new Value(List.of(new Value("a"), new Value("b"))),
                        "prop-G",
                        new Value(
                            new MutableStructure(
                                Map.of(
                                    "prop-H", new Value(),
                                    "prop-I", new Value())))))));
    assertThat(evaluationDetails.getValue()).isEqualTo(DEFAULT_VALUE);

    // malformed path without flag name
    evaluationDetails = client.getObjectDetails("...", DEFAULT_VALUE, SAMPLE_CONTEXT);
    assertThat(evaluationDetails.getErrorCode()).isEqualTo(GENERAL);
    assertThat(evaluationDetails.getVariant()).isBlank();
    assertThat(evaluationDetails.getReason()).isEqualTo("ERROR");
    assertThat(evaluationDetails.getErrorMessage()).isEqualTo("Illegal path string '...'");
    assertThat(evaluationDetails.getValue()).isEqualTo(DEFAULT_VALUE);
  }

  @Test
  public void booleanResolve() {
    mockSampleResponse();

    final FlagEvaluationDetails<Boolean> evaluationDetails =
        client.getBooleanDetails("flag.prop-A", true, SAMPLE_CONTEXT);

    assertThat(evaluationDetails.getValue()).isFalse();
    assertThat(evaluationDetails.getVariant()).isEqualTo("flags/flag/variants/var-A");
    assertThat(evaluationDetails.getErrorMessage()).isBlank();
    assertThat(evaluationDetails.getErrorCode()).isNull();
  }

  @Test
  public void stringResolve() {
    mockSampleResponse();

    final FlagEvaluationDetails<String> evaluationDetails =
        client.getStringDetails("flag.prop-B.prop-C", "default", SAMPLE_CONTEXT);

    assertThat(evaluationDetails.getValue()).isEqualTo("str-val");
    assertThat(evaluationDetails.getVariant()).isEqualTo("flags/flag/variants/var-A");
    assertThat(evaluationDetails.getErrorMessage()).isBlank();
    assertThat(evaluationDetails.getErrorCode()).isNull();
  }

  @Test
  public void integerResolve() {
    mockSampleResponse();

    final FlagEvaluationDetails<Integer> evaluationDetails =
        client.getIntegerDetails("flag.prop-E", 1000, SAMPLE_CONTEXT);

    assertThat(evaluationDetails.getValue()).isEqualTo(50);
    assertThat(evaluationDetails.getVariant()).isEqualTo("flags/flag/variants/var-A");
    assertThat(evaluationDetails.getErrorMessage()).isBlank();
    assertThat(evaluationDetails.getErrorCode()).isNull();
  }

  @Test
  public void doubleResolve() {
    mockSampleResponse();

    final FlagEvaluationDetails<Double> evaluationDetails =
        client.getDoubleDetails("flag.prop-B.prop-D", 10.5, SAMPLE_CONTEXT);

    assertThat(evaluationDetails.getValue()).isEqualTo(5.3);
    assertThat(evaluationDetails.getVariant()).isEqualTo("flags/flag/variants/var-A");
    assertThat(evaluationDetails.getErrorMessage()).isBlank();
    assertThat(evaluationDetails.getErrorCode()).isNull();
  }

  @Test
  public void longValueInIntegerSchemaResolveShouldFail() {
    mockSampleResponse(
        Collections.singletonList(
            new ValueSchemaHolder(
                "prop-X",
                Values.of(Integer.MAX_VALUE + 1L),
                FlagSchema.SchemaTypeCase.INT_SCHEMA)));

    final FlagEvaluationDetails<Integer> evaluationDetails =
        client.getIntegerDetails("flag.prop-X", 10, SAMPLE_CONTEXT);

    assertThat(evaluationDetails.getValue()).isEqualTo(10);
    assertThat(evaluationDetails.getVariant()).isNull();
    assertThat(evaluationDetails.getErrorMessage())
        .isEqualTo(
            "Mismatch between schema and value: value should be an int, but it is a double/long");
    assertThat(evaluationDetails.getErrorCode()).isEqualTo(ErrorCode.PARSE_ERROR);
  }

  @Test
  public void castingWithWrongType() {
    mockSampleResponse();

    final FlagEvaluationDetails<Boolean> evaluationDetails =
        client.getBooleanDetails("flag.prop-B.prop-C", true, SAMPLE_CONTEXT);
    assertThat(evaluationDetails.getValue()).isTrue();
    assertThat(evaluationDetails.getVariant()).isBlank();
    assertThat(evaluationDetails.getErrorMessage())
        .isEqualTo("Cannot cast value '%s' to expected type", new Value("str-val"));
    assertThat(evaluationDetails.getErrorCode()).isEqualTo(ErrorCode.TYPE_MISMATCH);
  }

  @Test
  public void shutdownShouldGiveStateNotReadyAndDefaultValues() {
    mockSampleResponse();
    openFeatureAPI.shutdown();

    final int defaultValue = 1000;
    final FlagEvaluationDetails<Integer> evaluationDetails =
        client.getIntegerDetails("flag.prop-E", defaultValue, SAMPLE_CONTEXT);

    final ProviderState state = openFeatureAPI.getProvider().getState();

    assertThat(state).isEqualTo(ProviderState.NOT_READY);
    assertThat(evaluationDetails.getValue()).isEqualTo(defaultValue);
  }

  //////
  // Utility
  //////

  private void mockResolve(
      BiConsumer<ResolveFlagsRequest, StreamObserver<ResolveFlagsResponse>> impl) {
    doAnswer(
            invocation -> {
              final ResolveFlagsRequest ResolveFlagsRequest = invocation.getArgument(0);
              final StreamObserver<ResolveFlagsResponse> streamObserver = invocation.getArgument(1);

              impl.accept(ResolveFlagsRequest, streamObserver);
              return null;
            })
        .when(serviceImpl)
        .resolveFlags(any(), any());
  }

  private void mockSampleResponse() {
    mockSampleResponse(Collections.emptyList());
  }

  private void mockSampleResponse(List<ValueSchemaHolder> additionalProps) {
    mockResolve(
        (resolveFlagRequest, streamObserver) -> {
          assertThat(resolveFlagRequest.getFlags(0)).isEqualTo("flags/flag");
          streamObserver.onNext(generateSampleResponse(additionalProps));
          streamObserver.onCompleted();
        });
  }

  private static ResolvedFlag generateResolvedFlag(List<ValueSchemaHolder> additionalProps) {
    final Struct.Builder valueBuilder =
        Struct.newBuilder()
            .putAllFields(
                Map.of(
                    "prop-A",
                    Values.of(false),
                    "prop-B",
                    Values.of(
                        Structs.of(
                            "prop-C", Values.of("str-val"),
                            "prop-D", Values.of(5.3))),
                    "prop-E",
                    Values.of(50),
                    "prop-F",
                    Values.of(List.of(Values.of("a"), Values.of("b"))),
                    "prop-G",
                    Values.of(
                        Structs.of(
                            "prop-H", Values.ofNull(),
                            "prop-I", Values.ofNull()))));

    final StructFlagSchema.Builder schemaBuilder =
        StructFlagSchema.newBuilder()
            .putAllSchema(
                Map.of(
                    "prop-A",
                    FlagSchema.newBuilder()
                        .setBoolSchema(BoolFlagSchema.getDefaultInstance())
                        .build(),
                    "prop-B",
                    FlagSchema.newBuilder()
                        .setStructSchema(
                            StructFlagSchema.newBuilder()
                                .putAllSchema(
                                    Map.of(
                                        "prop-C",
                                        FlagSchema.newBuilder()
                                            .setStringSchema(StringFlagSchema.getDefaultInstance())
                                            .build(),
                                        "prop-D",
                                        FlagSchema.newBuilder()
                                            .setDoubleSchema(DoubleFlagSchema.getDefaultInstance())
                                            .build()))
                                .build())
                        .build(),
                    "prop-E",
                    FlagSchema.newBuilder()
                        .setIntSchema(IntFlagSchema.getDefaultInstance())
                        .build(),
                    "prop-F",
                    FlagSchema.newBuilder()
                        .setListSchema(
                            ListFlagSchema.newBuilder()
                                .setElementSchema(
                                    FlagSchema.newBuilder()
                                        .setStringSchema(StringFlagSchema.getDefaultInstance())
                                        .build())
                                .build())
                        .build(),
                    "prop-G",
                    FlagSchema.newBuilder()
                        .setStructSchema(
                            StructFlagSchema.newBuilder()
                                .putAllSchema(
                                    Map.of(
                                        "prop-H",
                                        FlagSchema.newBuilder()
                                            .setStringSchema(StringFlagSchema.getDefaultInstance())
                                            .build(),
                                        "prop-I",
                                        FlagSchema.newBuilder()
                                            .setIntSchema(IntFlagSchema.getDefaultInstance())
                                            .build()))
                                .build())
                        .build()));

    additionalProps.forEach(
        (valueSchemaHolder) -> {
          valueBuilder.putFields(valueSchemaHolder.prop, valueSchemaHolder.value);
          final FlagSchema.Builder builder = getSchemaBuilder(valueSchemaHolder);
          schemaBuilder.putSchema(valueSchemaHolder.prop, builder.build());
        });

    return ResolvedFlag.newBuilder()
        .setFlag("flags/flag")
        .setVariant("flags/flag/variants/var-A")
        .setValue(valueBuilder)
        .setFlagSchema(schemaBuilder)
        .build();
  }

  private static FlagSchema.Builder getSchemaBuilder(ValueSchemaHolder valueSchemaHolder) {
    final FlagSchema.Builder builder = FlagSchema.newBuilder();
    switch (valueSchemaHolder.schemaTypeCase) {
      case STRUCT_SCHEMA:
        builder.setStructSchema(StructFlagSchema.getDefaultInstance());
        break;
      case LIST_SCHEMA:
        builder.setListSchema(ListFlagSchema.getDefaultInstance());
        break;
      case INT_SCHEMA:
        builder.setIntSchema(IntFlagSchema.getDefaultInstance());
        break;
      case DOUBLE_SCHEMA:
        builder.setDoubleSchema(DoubleFlagSchema.getDefaultInstance());
        break;
      case STRING_SCHEMA:
        builder.setStringSchema(StringFlagSchema.getDefaultInstance());
        break;
      case BOOL_SCHEMA:
        builder.setBoolSchema(BoolFlagSchema.getDefaultInstance());
        break;
      case SCHEMATYPE_NOT_SET:
        break;
    }
    return builder;
  }

  private static class ValueSchemaHolder {
    public ValueSchemaHolder(
        String prop, com.google.protobuf.Value value, FlagSchema.SchemaTypeCase schemaTypeCase) {
      this.prop = prop;
      this.value = value;
      this.schemaTypeCase = schemaTypeCase;
    }

    String prop;
    com.google.protobuf.Value value;
    FlagSchema.SchemaTypeCase schemaTypeCase;
  }
}
