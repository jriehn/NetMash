
package cyrus.platform;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.*;
import java.io.*;
import java.net.*;
import java.nio.*;
import java.nio.channels.*;
import java.nio.channels.spi.*;

import cyrus.lib.JSON;

/**  This is the runnable container or kernel class.
  *  Requires cyrusconfig.db giving Threadpool size.
  *  Runs event loop, handles NIO, handles Threadpool
  *  and gives events/callbacks to runmodule.
  */
public class Kernel {

    //-----------------------------------------------------

    static public final int SOCKREADBUFFERSIZE = 2048; // * 60,000 = 120Mb
    static public final int FILEREADBUFFERSIZE = 4096;

    //-----------------------------------------------------

    static public boolean running=false;
    static public JSON   config;
    static public Module runmodule;

    static private ExecutorService threadPool;

    //-----------------------------------------------------

    static public void init(Module runmod){
        init(null, runmod);
    }

    static public void init(JSON conf, Module runmod){
        initConfig(conf);
        initNetwork();
        threadPool=Executors.newFixedThreadPool(config.intPathN("kernel:threadpool"));
        runmodule=runmod;
    }

    static public void run(){
        runmodule.run();
        new Thread(){ public void run(){ eventLoop(); } }.start();
    }

    //-----------------------------------------------------

    /** Post an Object here to put it into a queue to be
      * assigned its own thread. Comes back on threadedObject()
      * callback of Module interface.
    */
    static public void threadObject(final Object o){
        if(o==null) return;
        threadPool.execute(new Runnable(){public void run(){try{synchronized(o){runmodule.threadedObject(o);}}catch(Throwable t){t.printStackTrace();}}});
    }

    //-----------------------------------------------------

    static public void listen(int port, ChannelUser channeluser){
        ServerSocketChannel listener = null;
        try {
            listener = ServerSocketChannel.open();
            listener.configureBlocking(false);
            listener.socket().setReuseAddress(true);
            listener.socket().bind(new InetSocketAddress(port), 1024);
            selock.lock(); try{ selector.wakeup();
            listener.register(selector, SelectionKey.OP_ACCEPT);
            listeners.put(listener, channeluser);
            }finally{ selock.unlock(); }

        } catch(BindException e){ logErr("Could not bind to port "+port, e, listener);
        } catch(Exception     e){ logErr("Could not bind to port "+port, e, listener); }
    }

    static public SocketChannel channelConnect(String host, int port, ChannelUser channeluser){
        SocketChannel channel = null;
        try{
            channel = SocketChannel.open();
            channel.configureBlocking(false);
            channel.socket().setTcpNoDelay(true);
            if(config.intPathN("network:log")==2) logOut("DNS/InetSocketAddress..");
            channel.connect(new InetSocketAddress(host, port));
            if(config.intPathN("network:log")==2) logOut("..DNS done");
            selock.lock(); try{ selector.wakeup();
            channel.register(selector, SelectionKey.OP_CONNECT);
            channels.put(channel, channeluser);
            }finally{ selock.unlock(); }

        } catch(Exception e){
            channels.remove(channel);
            logErr("Could not connect to "+host+":"+port, e, channel);
            channeluser.readable(channel, null, -1);
            return null;
        }

        return channel;
    }

    static public void send(SocketChannel channel, ByteBuffer bytebuffer){
        appendToWriteBuffers(channel, bytebuffer);
        setWriting(channel);
    }

    static public void checkWritable(SocketChannel channel){
        doCheckWritable(channel);
    }

    static public void readFile(File file, FileUser fileuser) throws Exception{
        if(!(file.exists() && file.canRead())) throw new Exception("Can't read file "+file.getPath());
        FileChannel channel    = new FileInputStream(file).getChannel();
        ByteBuffer  bytebuffer = ByteBuffer.allocate(FILEREADBUFFERSIZE);
        int len=0;
        while(len!= -1){
            len=channel.read(bytebuffer);
            fileuser.readable(bytebuffer, len);
            bytebuffer=ensureBufferBigEnough(bytebuffer);
        }
        channel.close();
    }

    static public void readFile(InputStream is, FileUser fileuser) throws Exception{
        byte[] buffer = new byte[FILEREADBUFFERSIZE];
        ByteBuffer bytebuffer = ByteBuffer.allocate(FILEREADBUFFERSIZE);
        int len=0;
        while(len!= -1){
            int l=bytebuffer.remaining();
            if(l>FILEREADBUFFERSIZE) l=FILEREADBUFFERSIZE;
            len=is.read(buffer, 0, l);
            if(len!= -1) bytebuffer.put(buffer, 0, len);
            fileuser.readable(bytebuffer, len);
            bytebuffer=ensureBufferBigEnough(bytebuffer);
        }
    }

    static public ByteBuffer readFile(File file, ByteBuffer bytebuffer) throws Exception{
        if(!(file.exists() && file.canRead())) throw new Exception("Can't read file "+file.getPath());
        FileChannel channel = new FileInputStream(file).getChannel();
        int len=0;
        while(len!= -1){
            len=channel.read(bytebuffer);
            bytebuffer=ensureBufferBigEnough(bytebuffer);
        }
        channel.close();
        return bytebuffer;
    }

    static public ByteBuffer readFile(InputStream is, ByteBuffer bytebuffer) throws Exception{
        byte[] buffer = new byte[FILEREADBUFFERSIZE];
        int len=0;
        while(len!= -1){
            int l=bytebuffer.remaining();
            if(l>FILEREADBUFFERSIZE) l=FILEREADBUFFERSIZE;
            len=is.read(buffer, 0, l);
            if(len!= -1) bytebuffer.put(buffer, 0, len);
            bytebuffer=ensureBufferBigEnough(bytebuffer);
        }
        return bytebuffer;
    }

    static public void writeFile(File file, boolean append, ByteBuffer bytebuffer, FileUser fileuser) throws Exception{
        FileChannel channel = new FileOutputStream(file, append).getChannel();
        int n=channel.write(bytebuffer);
        fileuser.writable(bytebuffer, n);
        channel.close();
    }

    static public void writeFile(FileOutputStream os, ByteBuffer bytebuffer, FileUser fileuser) throws Exception{
        FileChannel channel = os.getChannel();
        int n=channel.write(bytebuffer);
        fileuser.writable(bytebuffer, n);
    }

    static public ByteBuffer chopAtDivider(ByteBuffer bytebuffer, byte[] divider){
        return doChopAtDivider(bytebuffer, divider, false);
    }

    static public ByteBuffer chopAtDivider(ByteBuffer bytebuffer, byte[] divider, boolean leaveDivider){
        return doChopAtDivider(bytebuffer, divider, leaveDivider);
    }

    static public ByteBuffer chopAtDivider(ByteBuffer bytebuffer, byte[] divider, ByteBuffer bb){
        return doChopAtDivider(bytebuffer, divider, bb);
    }

    static public ByteBuffer chopAtLength(ByteBuffer bytebuffer, int length){
        return doChopAtLength(bytebuffer, length);
    }

    static public ByteBuffer chopAtLength(ByteBuffer bytebuffer, int length, ByteBuffer bb){
        return doChopAtLength(bytebuffer, length, bb);
    }

    static public void close(SocketChannel channel){
        closeSelectableChannel(channel);
    }

    static public ByteBuffer readBufferForChannel(SocketChannel channel){
        return rdbuffers.get(channel);
    }

    static public String IPtoString(){
        if(IP()==null) return "127.0.0.1";
        return IP().getHostAddress();
    }

    static private InetAddress IP=null;

    static public InetAddress IP(){
        if(IP==null) IP=findTheMainIP4AddressOfThisHost();
        return IP;
    }

    static public InetAddress findTheMainIP4AddressOfThisHost(){ try{
        Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
        while(interfaces.hasMoreElements()){
            NetworkInterface ni=interfaces.nextElement();
            if(!ni.isUp() || ni.isLoopback() || ni.isVirtual()) continue;
            Enumeration<InetAddress> addresses=ni.getInetAddresses();
            while(addresses.hasMoreElements()){
                InetAddress ad=addresses.nextElement();
                if(ad.isLoopbackAddress() || !(ad instanceof Inet4Address)) continue;
                return ad;
            }
        }
        } catch(Throwable t){ t.printStackTrace(); }
        return null;
    }

    static public HashSet<InetAddress> getBroadcastAddresses(){
        HashSet<InetAddress> r=new HashSet<InetAddress>();
        try{
        Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
        while(interfaces.hasMoreElements()){
            NetworkInterface ni=interfaces.nextElement();
            if(!ni.isUp() /* || ni.isLoopback() || ni.isVirtual() */) continue;
            for(InterfaceAddress address: ni.getInterfaceAddresses()){
logOut("---interface: "+ni);
logOut("---------address: "+address);
logOut("------------b/c: "+address.getBroadcast());
                InetAddress ia=address.getBroadcast();
                if(ia!=null) r.add(ia);
            }
        }
        } catch(Throwable t){ t.printStackTrace(); }
        return r;
    }

    static public void broadcastUDP(InetAddress addr, int port, String message){ try{
        DatagramSocket socket = new DatagramSocket();
        DatagramPacket packet = new DatagramPacket(message.getBytes(), message.length(), addr, port);
        socket.setBroadcast(true);
        socket.send(packet);
        } catch(Throwable t){ t.printStackTrace(); }
    }

    static LinkedHashMap<Integer,DatagramSocket> listenUDPPorts=new LinkedHashMap<Integer,DatagramSocket>();

    static public String listenUDP(int port){ try{
        DatagramSocket socket=listenUDPPorts.get(port);
        if(socket==null){
            socket = new DatagramSocket(port);
            listenUDPPorts.put(port, socket);
        }
        byte[] buf = new byte[4096];
        DatagramPacket packet = new DatagramPacket(buf, buf.length);
        socket.receive(packet);
        return new String(packet.getData(),0,packet.getLength());

        } catch(Throwable t){ t.printStackTrace(); }
        return null;
    }

    //-----------------------------------------------------

    static private Selector selector;
    static private ReentrantLock selock = new ReentrantLock();
    static private HashMap<ServerSocketChannel,ChannelUser> listeners = new HashMap<ServerSocketChannel,ChannelUser>();
    static private HashMap<SocketChannel,ChannelUser>       channels  = new HashMap<SocketChannel,ChannelUser>();
    static public  HashMap<SocketChannel,ByteBuffer>        rdbuffers = new HashMap<SocketChannel,ByteBuffer>();
    static public  HashMap<SocketChannel,Queue<ByteBuffer>> wrbuffers = new HashMap<SocketChannel,Queue<ByteBuffer>>();

    //-----------------------------------------------------

    static private void initConfig(JSON conf){
        try{
            if(conf==null) config = new JSON(new File("./cyrusconfig.db"),true);
            else           config = conf;
        } catch(Exception e){ bailOut("Error in config file", e, null); }
    }

    static private void initNetwork(){
        try{
            selector = Selector.open();
        } catch(Exception e){ bailOut("Could not open selector", e, null); }
    }

    //-----------------------------------------------------

    static private int failwait=0;

    static private void eventLoop(){

        running=true;
        logOut("Running "+config.stringPathN("name"));

        while(true){
            try {
                checkSelector();
                int i=selector.select();
                selock.lock(); selock.unlock();
                if(i==0 && !Thread.interrupted()) throw new RuntimeException("select returned nothing but not interrupted?!");
                if(i!=0) failwait=0;
            }catch(Throwable t) {
                if(failwait>1000){
                    logErr("Failure in event loop: "+t+" .. waiting for "+(failwait-500)+"ms");
                    sleep(failwait-500);
                }
                failwait+=250;
                if(failwait>10000) failwait=10000;
            }
        }
    }

    //-----------------------------------------------------

    static private void checkSelector(){

        Iterator<SelectionKey> iterator = selector.selectedKeys().iterator();

        while(iterator.hasNext()){

            SelectionKey key=null;
            try {
                key = iterator.next();
                iterator.remove();
                actOnKey(key);

            }catch(CancelledKeyException e){
                continue;
            }catch(Exception e) {
                if(e.getMessage()!=null) logErr("Checking selectors: "+e.getMessage()+" "+e.getClass());
                else e.printStackTrace();
                cancelKey(key);
                continue;
            }
        }
    }

    static private void actOnKey(SelectionKey key) throws Exception{

        if(key.isAcceptable())  acceptKey(key);
        else
        if(key.isConnectable()) connectKey(key);
        else
        if(key.isReadable())    readKey(key);
        else
        if(key.isWritable())    writeKey(key);
    }

    static private void acceptKey(SelectionKey key) throws Exception{

        ServerSocketChannel listener = (ServerSocketChannel)key.channel();
        SocketChannel channel = listener.accept();
        if(channel==null) return;

        channel.configureBlocking(false);
        channel.socket().setTcpNoDelay(true);
        selock.lock(); try{ selector.wakeup();
        channel.register(selector, SelectionKey.OP_READ);

        ChannelUser channeluser = listeners.get(listener);
        channeluser.readable(channel, null, 0);

        channels.put(channel, channeluser);
        }finally{ selock.unlock(); }
    }

    static private void connectKey(SelectionKey key) throws Exception{
        SocketChannel channel = (SocketChannel)key.channel();
        if(channel.isConnectionPending()) channel.finishConnect();
        writeKey(key);
    }

    static private void readKey(SelectionKey key) throws Exception{

        SocketChannel channel = (SocketChannel)key.channel();
        ByteBuffer    bytebuffer = rdbuffers.get(channel);
        if(bytebuffer == null){
            bytebuffer=ByteBuffer.allocate(SOCKREADBUFFERSIZE);
            rdbuffers.put(channel, bytebuffer);
        }

        int n = channel.read(bytebuffer);

        if(n== -1) {
            cancelKey(key);
            return;
        }
        ChannelUser channeluser = channels.get(channel);
        channeluser.readable(channel, bytebuffer, n);
        ensureBufferBigEnough(channel, bytebuffer);
    }

    static private void writeKey(SelectionKey key) throws Exception{

        SocketChannel     channel  = (SocketChannel)key.channel();
        Queue<ByteBuffer> bytebuffers = wrbuffers.get(channel);
        if(bytebuffers == null || bytebuffers.size()==0){
            ChannelUser channeluser = channels.get(channel);
            channeluser.writable(channel, null, 0);
            bytebuffers = wrbuffers.get(channel);
            if(bytebuffers == null || bytebuffers.size()==0) unSetWriting(channel);
            return;
        }
        ByteBuffer bytebuffer=bytebuffers.element();

        int n = channel.write(bytebuffer);

        chopFromWrbuffers(bytebuffers);
        ChannelUser channeluser = channels.get(channel);
        channeluser.writable(channel, bytebuffers, n);

        if(bytebuffers.size()==0){
            unSetWriting(channel);
        }
    }

    static private void doCheckWritable(SocketChannel channel){
        Queue<ByteBuffer> bytebuffers = wrbuffers.get(channel);
        if(bytebuffers == null || bytebuffers.size()==0){
            ChannelUser channeluser = channels.get(channel);
            channeluser.writable(channel, null, 0);
        }
    }

    //-----------------------------------------------------

    static private ByteBuffer doChopAtDivider(ByteBuffer bytebuffer, byte[] divider, boolean leaveDivider){
        int len;
        int pos=bytebuffer.position();
        for(len=0; len<=pos-divider.length; len++){
            int j;
            for(j=0; j< divider.length; j++){
                if(bytebuffer.get(len+j)!=divider[j]) break;
            }
            if(j!=divider.length) continue;

            int cut =len+divider.length;
            int take=leaveDivider? cut: len;
            ByteBuffer bb=ByteBuffer.allocate(take);

            return dumpInto(bytebuffer, take, cut, bb);
        }
        return null;
    }

    static private ByteBuffer doChopAtDivider(ByteBuffer bytebuffer, byte[] divider, ByteBuffer bb){
        int len;
        int pos=bytebuffer.position();
        for(len=0; len<=pos-divider.length; len++){
            int j;
            for(j=0; j< divider.length; j++){
                if(bytebuffer.get(len+j)!=divider[j]) break;
            }
            if(j!=divider.length) continue;

            bb=ensureBufferBigEnough(bb, len);

            return dumpInto(bytebuffer, len, len+divider.length, bb);
        }
        bb.limit(bb.position());
        return bb;
    }

    static private ByteBuffer doChopAtLength(ByteBuffer bytebuffer, int len){

        int pos=bytebuffer.position();
        if(pos >= len){

            ByteBuffer bb=ByteBuffer.allocate(len);

            return dumpInto(bytebuffer, len, len, bb);
        }
        return null;
    }

    static private ByteBuffer doChopAtLength(ByteBuffer bytebuffer, int len, ByteBuffer bb){

        int pos=bytebuffer.position();
        if(pos >=len){

            bb=ensureBufferBigEnough(bb, len);

            return dumpInto(bytebuffer, len, len, bb);
        }
        bb.limit(bb.position());
        return bb;
    }

    static private ByteBuffer dumpInto(ByteBuffer bytebuffer, int lentake, int lencut, ByteBuffer bb){

        int pos=bytebuffer.position();

        bytebuffer.position(0);
        bytebuffer.limit(lentake);

        bb.limit(bb.capacity());
        bb.put(bytebuffer);
        bb.limit(bb.position());
        bb.position(0);

        bytebuffer.limit(pos);
        bytebuffer.position(lencut);
        bytebuffer.compact();

        return bb;
    }

    static private void appendToWriteBuffers(SocketChannel channel, ByteBuffer bytebuffer){
        Queue<ByteBuffer> bytebuffers = wrbuffers.get(channel);
        if(bytebuffers == null){
            bytebuffers = new LinkedList<ByteBuffer>();
            wrbuffers.put(channel, bytebuffers);
        }
        bytebuffers.add(bytebuffer);
    }

    static private ByteBuffer ensureBufferBigEnough(ByteBuffer bytebuffer){
        if(bytebuffer.position() != bytebuffer.capacity()) return bytebuffer;
        ByteBuffer biggerbuffer = ByteBuffer.allocate(bytebuffer.capacity()*2);
        bytebuffer.flip();
        biggerbuffer.put(bytebuffer);
        if(biggerbuffer.capacity() >= 64 * 1024){
            logOut("New file read buffer size="+biggerbuffer.capacity());
        }
        return biggerbuffer;
    }

    static private ByteBuffer ensureBufferBigEnough(ByteBuffer bytebuffer, int len){
        if(bytebuffer.position()+len <= bytebuffer.capacity()) return bytebuffer;
        ByteBuffer biggerbuffer = ByteBuffer.allocate((bytebuffer.position()+len)*2);
        bytebuffer.flip();
        biggerbuffer.put(bytebuffer);
        if(biggerbuffer.capacity() >= 64 * 1024){
            logOut("New file read buffer size="+biggerbuffer.capacity());
        }
        return biggerbuffer;
    }

    static private void ensureBufferBigEnough(SocketChannel channel, ByteBuffer bytebuffer){
        if(bytebuffer.position() != bytebuffer.capacity()) return;
        ByteBuffer biggerbuffer = ByteBuffer.allocate(bytebuffer.capacity()*2);
        bytebuffer.flip();
        biggerbuffer.put(bytebuffer);
        bytebuffer = biggerbuffer;
        rdbuffers.put(channel, bytebuffer);
        if(bytebuffer.capacity() >= 64 * 1024){
            logOut("New socket read buffer size="+bytebuffer.capacity());
        }
    }

    static private void chopFromWrbuffers(Queue<ByteBuffer> bytebuffers){
        if(bytebuffers.element().remaining() == 0){
            bytebuffers.remove();
        }
    }

    static private void setWriting(SocketChannel channel){
        try{
            selock.lock(); try{ selector.wakeup();
            channel.register(selector, SelectionKey.OP_WRITE);
            }finally{ selock.unlock(); }
        }catch(ClosedChannelException cce){
            logErr("Attempt to write to closed channel");
        }
    }

    static private void unSetWriting(SocketChannel channel){
        try{
            selock.lock(); try{ selector.wakeup();
            channel.register(selector, SelectionKey.OP_READ);
            }finally{ selock.unlock(); }
        }catch(ClosedChannelException cce){ }
    }

    //-----------------------------------------------------

    static private void cancelKey(SelectionKey key){
        try{ key.cancel(); }catch(Exception e){}
        closeSelectableChannel(key.channel());
    }

    static private void closeSelectableChannel(SelectableChannel channel){
        try{
            channel.close();
        }catch(Exception e){ logErr("channel.close",e,null); }

        ChannelUser lmodule = listeners.remove(channel);
        if(lmodule != null) return;

        ChannelUser channeluser = channels.remove(channel);
        ByteBuffer  bytebuffer = rdbuffers.remove(channel); wrbuffers.remove(channel);
        if(channeluser != null) channeluser.readable((SocketChannel)channel, bytebuffer, -1);
    }

    static private void logOut(Object message){
        System.out.println("Kernel: "+message);
    }

    static private void logErr(Object message){
        System.err.println("Kernel: "+message);
    }

    static private void logErr(String message, Throwable t, AbstractInterruptibleChannel c){
        System.err.println("Kernel: "+message+":");
        t.printStackTrace();
        try{ if(c!=null) c.close(); } catch(Throwable tt){}
    }

    static private void bailOut(String message, Throwable t, AbstractInterruptibleChannel c){
        System.err.println("Kernel: Bailing! "+message+":");
        t.printStackTrace();
        try{ if(c!=null) c.close(); } catch(Throwable tt){}
        System.exit(1);
    }

    //-----------------------------------------------------

    static public void sleep(long millis){
        try{ Thread.sleep(millis); }catch(Exception e){}
    }

    //-----------------------------------------------------

}

