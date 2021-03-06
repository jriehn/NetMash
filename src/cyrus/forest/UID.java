package cyrus.forest;

import java.util.*;
import java.io.*;
import java.net.*;
import java.nio.*;
import java.nio.charset.*;
import java.util.concurrent.*;
import java.util.regex.*;

import cyrus.platform.*;

import static cyrus.lib.Utils.*;

public class UID {

    // ----------------------------------------

    private String uid;

    public UID(){
        uid = generateUID();
    }

    public UID(String uid){
        this.uid=uid;
    }

    public String toString(){
        return uid;
    }

    // ----------------------------------------

    static public int generateVersion(){
        return random(10000,100000);
    }

    static public String generateCN(){
        return ("c-n-"+fourHex()+"-"+fourHex()+"-"+fourHex()+"-"+fourHex());
    }

    static public String generateUID(){
        return ("uid-"+fourHex()+"-"+fourHex()+"-"+fourHex()+"-"+fourHex());
    }

    static private String fourHex(){
        String h = "000"+Integer.toHexString((int)(Math.random()*0x10000));
        return h.substring(h.length()-4);
    }

    static public boolean isUID(Object o){
        if(o==null || !(o instanceof String)) return false;
        String uid=(String)o;
        return (uid.startsWith("uid-") || uid.startsWith("http://"));
    }

    static public String  URLPATHRE=null;
    static public Pattern URLPATHPA=null;
    static public Pattern URLPATHPA(){
        if(URLPATHRE==null){
            URLPATHRE = Kernel.config.stringPathN("network:pathprefix")+"((uid-[-0-9a-f]+).json|(uid-[-0-9a-f]+).cyr|(c-n-[-0-9a-f]+))$";
            URLPATHPA = Pattern.compile(URLPATHRE);
        }
        return URLPATHPA;
    }

    static public String toURL(String uid2url){
        if(uid2url.startsWith("http://")) return uid2url;
        if(notVisible()) return uid2url;
        boolean dotJSON=uid2url.startsWith("uid-");
        return localPrePath()+uid2url+(dotJSON? ".json": "");
    }

    static public String toUID(String url2uid){
        if(!url2uid.startsWith("http://"))         return url2uid;
        int s=url2uid.indexOf("uid-");  if(s== -1) return url2uid;
        int e=url2uid.indexOf(".json"); if(e!= -1) return url2uid.substring(s,e);
        ;   e=url2uid.indexOf(".cyr");  if(e!= -1) return url2uid.substring(s,e);
        ;                                          return url2uid.substring(s);
    }

    static public String normaliseUID(String baseurl, String uid2url){
        if(uid2url==null) return null;
        if(notVisible()) return toURLfromBaseURL(baseurl, uid2url);
        if(baseurl!=null && !baseurl.startsWith(localPre())) uid2url=toURLfromBaseURL(baseurl, uid2url);
        return uid2url.startsWith(localPre())? toUID(uid2url): uid2url;
    }

    @SuppressWarnings("unchecked")
    static public LinkedList normaliseUIDs(String baseurl, LinkedList<String> uid2urls){
        if(uid2urls==null) return null;
        LinkedList ll=new LinkedList();
        for(String uid2url: uid2urls) ll.add(normaliseUID(baseurl,uid2url));
        return ll;
    }

    static public String toUIDifLocal(String url2uid){
        if(notVisible() || url2uid==null) return url2uid;
        return url2uid.startsWith(localPre())? toUID(url2uid): url2uid;
    }

    static public String toURLfromBaseURL(String baseurl, String uid2url){
        if(baseurl==null)                  return uid2url;
        if(!baseurl.startsWith("http://")) return uid2url;
        if(!uid2url.startsWith("uid-"))    return uid2url;
        int s=baseurl.indexOf("uid-");
        return baseurl.substring(0,s)+uid2url+".json";
    }

    static private String localpre=null;
    static private String localpath=null;
    static Boolean notvisible=null;

    static public String localPre(){
        if(localpre==null){
            String host=Kernel.config.stringPathN("network:host");
            if(host==null) host=Kernel.IPtoString();
            localpre="http://"+host+":"+Kernel.config.intPathN("network:port");
        }
        return localpre;
    }

    static public String localPrePath(){
        if(localpath==null){
            localpath=localPre()+Kernel.config.stringPathN("network:pathprefix");
        }
        return localpath;
    }

    static boolean notVisible(){
        if(notvisible==null){
            notvisible=!Kernel.config.isAtPathN("network:port");
        }
        return notvisible;
    }

    // ----------------------------------------

    static public void main(String[] args){

        UID uid = new UID("uid-1");
        System.out.println(uid);

        assert !isUID(null);
        assert !isUID("");
        assert  isUID("http://foo");
        assert  isUID("uid-12-12");

        for(int i=1; i<=10; i++) System.out.println(new UID());
    }

    // ----------------------------------------
}


