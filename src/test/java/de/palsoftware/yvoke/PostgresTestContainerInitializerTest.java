package de.palsoftware.yvoke;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import com.github.dockerjava.api.model.Ports.Binding;
import java.util.List;
import java.util.ServiceLoader;
import org.junit.jupiter.api.Test;
import org.testcontainers.core.CreateContainerCmdModifier;

class PostgresTestContainerInitializerTest {

    @Test
    void serviceLoaderRegistersInitializerAsCreateContainerCmdModifierForAllTestcontainers() {
        List<CreateContainerCmdModifier> modifiers =
            ServiceLoader.load(CreateContainerCmdModifier.class).stream()
                .map(ServiceLoader.Provider::get).toList();

        assertThat(modifiers)
            .as("Testcontainers creates RyukContainer internally via ServiceLoader-discovered "
                + "CreateContainerCmdModifiers; without this registration Ryuk and any container "
                + "without an explicit modifier still bind [::]:0 on macOS")
            .anyMatch(PostgresTestContainerInitializer.class::isInstance);
    }

    @Test
    void modifyRewritesWildcardPortBindingsToIpv4LoopbackAndPreservesPortSpec() {
        HostConfig hostConfig = new HostConfig()
            .withPortBindings(List.of(new PortBinding(Binding.empty(), ExposedPort.tcp(5432)),
                new PortBinding(Binding.bindPort(15432), ExposedPort.tcp(8080)),
                new PortBinding(Binding.bindIpAndPort("10.0.0.1", 9000), ExposedPort.tcp(9000))));
        CreateContainerCmd cmd = mock(CreateContainerCmd.class);
        when(cmd.getHostConfig()).thenReturn(hostConfig);

        PostgresTestContainerInitializer initializer = new PostgresTestContainerInitializer();
        assertThat((Object) initializer).isInstanceOf(CreateContainerCmdModifier.class);
        ((CreateContainerCmdModifier) initializer).modify(cmd);

        Ports ports = hostConfig.getPortBindings();
        assertThat(ports.getBindings().get(ExposedPort.tcp(5432)))
            .containsExactly(new Binding("127.0.0.1", null));
        assertThat(ports.getBindings().get(ExposedPort.tcp(8080)))
            .containsExactly(new Binding("127.0.0.1", "15432"));
        assertThat(ports.getBindings().get(ExposedPort.tcp(9000)))
            .containsExactly(new Binding("10.0.0.1", "9000"));
    }
}
