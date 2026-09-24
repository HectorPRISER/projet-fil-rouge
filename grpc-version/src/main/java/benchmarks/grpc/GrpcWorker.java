package benchmarks.grpc;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.stub.StreamObserver;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

// Worker gRPC : ouvre un flux bidirectionnel unique vers le maitre (Distribute), s'annonce
// (ready=true), puis pour chaque Block recu calcule (CrackUtil) et renvoie un WorkerReport,
// jusqu'a recevoir stop=true. Une seule connexion HTTP/2, un seul flux, zero re-parsing
// texte a chaque bloc (contrairement a RestWorker : 1 requete HTTP + JSON par bloc).
//
// Lancement : java -cp target/crack-grpc-1.0-all.jar benchmarks.grpc.GrpcWorker <host> <port>
public class GrpcWorker {

    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws InterruptedException {
        String host = args[0];
        int port = Integer.parseInt(args[1]);

        ManagedChannel channel = ManagedChannelBuilder.forTarget("dns:///" + host + ":" + port)
                .usePlaintext().build();
        CrackServiceGrpc.CrackServiceStub stub = CrackServiceGrpc.newStub(channel);

        CountDownLatch done = new CountDownLatch(1);
        AtomicInteger blocksHandled = new AtomicInteger();

        // Holder mutable : requestObserver n'existe qu'apres stub.distribute(), mais le
        // callback de reponse (defini avant) doit pouvoir s'en servir pour repondre.
        StreamObserver<CrackProto.WorkerReport>[] requestObserver = new StreamObserver[1];

        requestObserver[0] = stub.distribute(new StreamObserver<CrackProto.Block>() {
            @Override
            public void onNext(CrackProto.Block block) {
                if (block.getStop()) {
                    requestObserver[0].onCompleted();
                    return;
                }
                try {
                    byte[] targetHash = CrackUtil.decodeHex(block.getTargetHash());
                    CrackUtil.ScanResult result = CrackUtil.scanBlock(
                            block.getLength(), block.getFirstChar().charAt(0), targetHash);
                    blocksHandled.incrementAndGet();

                    CrackProto.WorkerReport.Builder report = CrackProto.WorkerReport.newBuilder()
                            .setLength(block.getLength())
                            .setFirstChar(block.getFirstChar())
                            .setAttempts(result.attempts())
                            .setReady(false);
                    if (result.found() != null) {
                        report.setFound(result.found());
                    }
                    requestObserver[0].onNext(report.build());
                } catch (Exception e) {
                    requestObserver[0].onError(e);
                }
            }

            @Override
            public void onError(Throwable t) {
                System.err.println("Erreur flux : " + t.getMessage());
                done.countDown();
            }

            @Override
            public void onCompleted() {
                done.countDown();
            }
        });

        requestObserver[0].onNext(CrackProto.WorkerReport.newBuilder().setReady(true).build());

        done.await();
        channel.shutdownNow();
        System.out.printf("Termine : %d blocs traites%n", blocksHandled.get());
    }
}
