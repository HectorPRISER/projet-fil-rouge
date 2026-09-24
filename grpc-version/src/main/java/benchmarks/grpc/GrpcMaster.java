package benchmarks.grpc;

import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.stub.StreamObserver;

import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

// Version B (gRPC/Protobuf) : meme partitionnement que DistributedMaster/RestMaster,
// mais sur un flux bidirectionnel HTTP/2 (binaire, zero parsing texte). Chaque worker
// ouvre UN SEUL flux Distribute() : le maitre y pousse les blocs au fur et mesure que le
// worker renvoie ses rapports, sur la meme connexion (contrairement a REST : 1 requete
// HTTP par bloc, ou TCP texte : 1 ligne par bloc).
//
// Lancement : java -cp target/crack-grpc-1.0-all.jar benchmarks.grpc.GrpcMaster <port> <minLength> <maxLength> <targetHashHex>
public class GrpcMaster {

    private record Block(int length, char firstChar) {}

    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(args[0]);
        int minLength = Integer.parseInt(args[1]);
        int maxLength = Integer.parseInt(args[2]);
        String targetHashHex = args[3];

        BlockingQueue<Block> blocks = new LinkedBlockingQueue<>();
        for (int length = minLength; length <= maxLength; length++) {
            for (char c : CrackUtil.ALPHABET) {
                blocks.add(new Block(length, c));
            }
        }
        int totalBlocks = blocks.size();

        AtomicReference<String> found = new AtomicReference<>();
        AtomicLong totalAttempts = new AtomicLong();
        List<StreamObserver<CrackProto.Block>> activeStreams = new CopyOnWriteArrayList<>();
        long start = System.nanoTime();

        CrackServiceGrpc.AsyncService service = new CrackServiceGrpc.AsyncService() {
            @Override
            public StreamObserver<CrackProto.WorkerReport> distribute(
                    StreamObserver<CrackProto.Block> workerStream) {
                activeStreams.add(workerStream);
                return new StreamObserver<>() {
                    @Override
                    public void onNext(CrackProto.WorkerReport report) {
                        if (!report.getReady()) {
                            totalAttempts.addAndGet(report.getAttempts());
                            if (!report.getFound().isEmpty()) {
                                if (found.compareAndSet(null, report.getFound())) {
                                    broadcastStop(activeStreams);
                                }
                                return;
                            }
                        }
                        sendNextBlock(workerStream, blocks, targetHashHex, found);
                    }

                    @Override
                    public void onError(Throwable t) {
                        activeStreams.remove(workerStream);
                    }

                    @Override
                    public void onCompleted() {
                        activeStreams.remove(workerStream);
                    }
                };
            }
        };

        Server server = ServerBuilder.forPort(port)
                .addService(CrackServiceGrpc.bindService(service))
                .build().start();
        System.out.printf("Maitre gRPC en ecoute sur le port %d (%d blocs)%n", port, totalBlocks);

        while (found.get() == null && !blocks.isEmpty()) {
            Thread.sleep(20);
        }
        Thread.sleep(300); // grace : laisse les flux en cours se terminer
        server.shutdownNow();

        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        if (found.get() != null) {
            System.out.printf("Mot de passe trouve = \"%s\" (%d tentatives, %d ms)%n",
                    found.get(), totalAttempts.get(), elapsedMs);
        } else {
            System.out.printf("Echec, aucun mot ne correspond (%d tentatives, %d ms)%n",
                    totalAttempts.get(), elapsedMs);
        }
    }

    private static void sendNextBlock(StreamObserver<CrackProto.Block> stream, BlockingQueue<Block> blocks,
                                       String targetHashHex, AtomicReference<String> found) {
        if (found.get() != null) {
            stream.onNext(CrackProto.Block.newBuilder().setStop(true).build());
            stream.onCompleted();
            return;
        }
        Block block = blocks.poll();
        if (block == null) {
            stream.onNext(CrackProto.Block.newBuilder().setStop(true).build());
            stream.onCompleted();
            return;
        }
        stream.onNext(CrackProto.Block.newBuilder()
                .setStop(false)
                .setLength(block.length())
                .setFirstChar(String.valueOf(block.firstChar()))
                .setTargetHash(targetHashHex)
                .build());
    }

    private static void broadcastStop(List<StreamObserver<CrackProto.Block>> activeStreams) {
        CrackProto.Block stop = CrackProto.Block.newBuilder().setStop(true).build();
        for (StreamObserver<CrackProto.Block> s : activeStreams) {
            try {
                s.onNext(stop);
                s.onCompleted();
            } catch (Exception ignored) {
                // flux deja ferme par le worker
            }
        }
    }
}
