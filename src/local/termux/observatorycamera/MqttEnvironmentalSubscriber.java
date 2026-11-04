/*
 * Copyright (C) 2026 David González López-Tercero
 * SPDX-License-Identifier: GPL-3.0-or-later
 * See LICENSE in the repository root for licensing details.
 */

package local.termux.observatorycamera;

import android.content.Context;
import com.hivemq.client.mqtt.MqttClient;
import com.hivemq.client.mqtt.MqttClientState;
import com.hivemq.client.mqtt.mqtt3.Mqtt3ClientBuilder;
import com.hivemq.client.mqtt.datatypes.MqttQos;
import com.hivemq.client.mqtt.mqtt3.Mqtt3AsyncClient;
import com.hivemq.client.mqtt.mqtt3.message.connect.Mqtt3ConnectBuilder;
import com.hivemq.client.mqtt.mqtt3.message.publish.Mqtt3Publish;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/** One non-blocking HiveMQ subscriber owned by the explicitly started HTTP service lifecycle. */
final class MqttEnvironmentalSubscriber {
    private final EnvironmentalTelemetry telemetry;
    private Mqtt3AsyncClient client;
    private MqttSubscriptionLifecycle lifecycle;

    MqttEnvironmentalSubscriber(EnvironmentalTelemetry telemetry) { this.telemetry = telemetry; }

    synchronized void start(Context context, CaptureConfig config) {
        stop();
        if (config.mqttHost.isEmpty()) {
            telemetry.setConnectionState("disabled", null);
            return;
        }
        telemetry.setConnectionState("connecting", null);
        try {
            final MqttSubscriptionLifecycle run = new MqttSubscriptionLifecycle();
            lifecycle = run;
            String clientId = MqttClientIdStore.get(context.getFilesDir());
            Mqtt3ClientBuilder builder = MqttClient.builder().useMqttVersion3()
                .identifier(clientId).serverHost(config.mqttHost).serverPort(config.mqttPort)
                .automaticReconnect().initialDelay(1, TimeUnit.SECONDS).maxDelay(120, TimeUnit.SECONDS)
                .applyAutomaticReconnect()
                .addConnectedListener(connected -> subscribe(config.mqttTopic, run))
                .addDisconnectedListener(disconnected -> {
                    if (run.isActive())
                        telemetry.setConnectionState("disconnected", "connection-lost");
                });
            if (config.mqttTls) builder = builder.sslWithDefaultConfig();
            final Mqtt3AsyncClient started = builder.buildAsync();
            client = started;
            MqttCredentialsStore.Credentials credentials = MqttCredentialsStore.read(context);
            Mqtt3ConnectBuilder.Send<java.util.concurrent.CompletableFuture<com.hivemq.client.mqtt.mqtt3.message.connect.connack.Mqtt3ConnAck>>
                connect = started.connectWith().cleanSession(true).keepAlive(30);
            if (credentials.username != null) {
                ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder().encode(CharBuffer.wrap(credentials.password));
                byte[] password = new byte[encoded.remaining()];
                encoded.get(password);
                connect.simpleAuth().username(credentials.username).password(password).applySimpleAuth()
                    .send().whenComplete((ack, error) -> {
                        java.util.Arrays.fill(password, (byte) 0);
                        java.util.Arrays.fill(credentials.password, '\0');
                        connected(error, run);
                    });
            } else {
                connect.send().whenComplete((ack, error) -> connected(error, run));
            }
        } catch (Exception e) {
            closeClient();
            telemetry.setConnectionState("error", e instanceof IllegalArgumentException
                ? "invalid-credentials" : "subscriber-unavailable");
        }
    }

    private void connected(Throwable error, MqttSubscriptionLifecycle run) {
        if (error != null && run.isActive()) telemetry.setConnectionState("disconnected", "connection-failed");
    }

    private void subscribe(String topic, MqttSubscriptionLifecycle run) {
        Mqtt3AsyncClient current;
        synchronized (this) { current = client; }
        if (current == null || !run.isActive()) return;
        boolean accepted = run.connected(() -> current.subscribeWith().topicFilter(topic)
            .qos(MqttQos.AT_MOST_ONCE).callback(publish -> receive(publish, current, run))
            .send().whenComplete((ack, error) -> {
                if (!isCurrent(current) || !run.isActive()) return;
                telemetry.setConnectionState(error == null ? "waiting" : "error",
                    error == null ? null : "subscription-failed");
            }));
        if (!accepted && run.isActive()) telemetry.setConnectionState("error", "subscription-failed");
    }

    private void receive(Mqtt3Publish publish, Mqtt3AsyncClient current, MqttSubscriptionLifecycle run) {
        if (!isCurrent(current) || !run.isActive()) return;
        ByteBuffer payload = publish.getPayload().orElse(ByteBuffer.allocate(0)).asReadOnlyBuffer();
        if (payload.remaining() > EnvironmentalTelemetry.MAX_PAYLOAD_BYTES) {
            telemetry.setConnectionState("error", "invalid-sensor-payload");
            return;
        }
        byte[] bytes = new byte[payload.remaining()];
        payload.get(bytes);
        if (!telemetry.accept(bytes, publish.isRetain(), System.currentTimeMillis()))
            telemetry.setConnectionState("error", "invalid-sensor-payload");
    }

    synchronized void stop() {
        if (lifecycle != null) lifecycle.stop();
        lifecycle = null;
        Mqtt3AsyncClient current = client;
        client = null;
        if (current != null && current.getState() != MqttClientState.DISCONNECTED) {
            try { current.disconnect(); } catch (RuntimeException ignored) { }
        }
    }

    private synchronized void closeClient() { stop(); }

    private synchronized boolean isCurrent(Mqtt3AsyncClient expected) { return client == expected; }

}
