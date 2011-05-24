package com.limegroup.gnutella.downloader;

import java.io.IOException;
import java.util.concurrent.ScheduledExecutorService;

import org.limewire.io.InvalidDataException;

import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.inject.name.Named;
import com.limegroup.gnutella.ApplicationServices;
import com.limegroup.gnutella.DownloadCallback;
import com.limegroup.gnutella.DownloadManager;
import com.limegroup.gnutella.FileManager;
import com.limegroup.gnutella.MessageRouter;
import com.limegroup.gnutella.NetworkManager;
import com.limegroup.gnutella.SaveLocationException;
import com.limegroup.gnutella.SavedFileManager;
import com.limegroup.gnutella.UrnCache;
import com.limegroup.gnutella.altlocs.AltLocManager;
import com.limegroup.gnutella.altlocs.AlternateLocationFactory;
import com.limegroup.gnutella.auth.ContentManager;
import com.limegroup.gnutella.downloader.serial.DownloadMemento;
import com.limegroup.gnutella.downloader.serial.InNetworkDownloadMemento;
import com.limegroup.gnutella.downloader.serial.InNetworkDownloadMementoImpl;
import com.limegroup.gnutella.filters.IPFilter;
import com.limegroup.gnutella.guess.OnDemandUnicaster;
import com.limegroup.gnutella.messages.QueryRequest;
import com.limegroup.gnutella.messages.QueryRequestFactory;
import com.limegroup.gnutella.tigertree.HashTreeCache;
import com.limegroup.gnutella.version.DownloadInformation;

/**
 * A downloader that works in the background, using the network to continue itself.
 */
class InNetworkDownloaderImpl extends ManagedDownloaderImpl implements InNetworkDownloader {
    
    private String tigerTreeRoot;
    private long startTime;
    private int downloadAttempts;
    
    /** 
     * Constructs a new downloader that's gonna work off the network.
     * @param pushListProvider TODO
     */
    @Inject
    InNetworkDownloaderImpl(DownloadManager downloadManager,
            FileManager fileManager, 
            DownloadCallback downloadCallback, NetworkManager networkManager,
            AlternateLocationFactory alternateLocationFactory, RequeryManagerFactory requeryManagerFactory,
            QueryRequestFactory queryRequestFactory, OnDemandUnicaster onDemandUnicaster,
            DownloadWorkerFactory downloadWorkerFactory, AltLocManager altLocManager,
            ContentManager contentManager, SourceRankerFactory sourceRankerFactory,
            UrnCache urnCache, SavedFileManager savedFileManager,
            VerifyingFileFactory verifyingFileFactory, DiskController diskController,
             IPFilter ipFilter, @Named("backgroundExecutor") ScheduledExecutorService backgroundExecutor,
            Provider<MessageRouter> messageRouter, Provider<HashTreeCache> tigerTreeCache,
            ApplicationServices applicationServices, RemoteFileDescFactory remoteFileDescFactory, Provider<PushList> pushListProvider) throws SaveLocationException {
        super(downloadManager, fileManager,
                downloadCallback, networkManager, alternateLocationFactory, requeryManagerFactory,
                queryRequestFactory, onDemandUnicaster, downloadWorkerFactory, altLocManager,
                contentManager, sourceRankerFactory, urnCache, savedFileManager,
                verifyingFileFactory, diskController, ipFilter, backgroundExecutor, messageRouter,
                tigerTreeCache, applicationServices, remoteFileDescFactory, pushListProvider);
    }
    
    /* (non-Javadoc)
     * @see com.limegroup.gnutella.downloader.InNetworkDownloader#initDownloadInformation(com.limegroup.gnutella.version.DownloadInformation, long)
     */
    public void initDownloadInformation(DownloadInformation downloadInformation, long startTime) {
        // note: even though we support bigger files, this is a good sanity check
        if (downloadInformation.getSize() > Integer.MAX_VALUE)
            throw new IllegalArgumentException("size too big for now.");
        setContentLength(downloadInformation.getSize());
        if(downloadInformation.getUpdateURN() != null)
            setSha1Urn(downloadInformation.getUpdateURN());
        setTigerTreeRoot(downloadInformation.getTTRoot());
        setStartTime(startTime);
        setDownloadAttempts(0);
    }    
    
    protected synchronized void setDownloadAttempts(int i) {
        this.downloadAttempts = i;
    }

    protected synchronized void setStartTime(long startTime) {
        this.startTime = startTime;
    }

    protected synchronized void setTigerTreeRoot(String tigerTreeRoot) {
        this.tigerTreeRoot = tigerTreeRoot;
    }
    
    /**
     * Gets a new SourceRanker, using only LegacyRanker (not PingRanker).
     */
    @Override
    protected SourceRanker getSourceRanker(SourceRanker oldRanker) {
        if(oldRanker != null)
            return oldRanker;
        else
            return new LegacyRanker();
    }
        
    /* (non-Javadoc)
     * @see com.limegroup.gnutella.downloader.InNetworkDownloader#startDownload()
     */
    @Override
    public synchronized void startDownload() {
        incrementDownloadAttempts();
        super.startDownload();
    }
    
    private synchronized void incrementDownloadAttempts() {
        downloadAttempts++;
    }

    @Override
    protected boolean shouldValidate() {
        return false;
    }
    
    /**
     * Ensures that the VerifyingFile knows what TTRoot we're expecting.
     */
    @Override
    protected void initializeVerifyingFile() throws IOException {
        super.initializeVerifyingFile();
        if(commonOutFile != null) {
            commonOutFile.setExpectedHashTreeRoot(getTigerTreeRoot());
        }
    }
    
    protected synchronized String getTigerTreeRoot() {
        return tigerTreeRoot;
    }

    /** Sends a targeted query for this. */
    @Override
    public synchronized QueryRequest newRequery() throws CantResumeException {
        QueryRequest qr = super.newRequery();
        qr.setTTL((byte) 2);
        return qr;
    }

    /*
     * (non-Javadoc)
     * 
     * @see com.limegroup.gnutella.downloader.InNetworkDownloader#getNumAttempts()
     */
    public synchronized int getDownloadAttempts() {
        return downloadAttempts;
    }
    
    /* (non-Javadoc)
     * @see com.limegroup.gnutella.downloader.InNetworkDownloader#getStartTime()
     */
    public synchronized long getStartTime() {
        return startTime;
    }
    
    @Override
    public DownloaderType getDownloadType() {
        return DownloaderType.INNETWORK;
    }
    
    @Override
    protected void fillInMemento(DownloadMemento memento) {
        super.fillInMemento(memento);
        InNetworkDownloadMemento imem = (InNetworkDownloadMemento)memento;
        imem.setTigerTreeRoot(getTigerTreeRoot());
        imem.setStartTime(getStartTime());
        imem.setDownloadAttempts(getDownloadAttempts());
    }
    
    @Override
    protected DownloadMemento createMemento() {
        return new InNetworkDownloadMementoImpl();
    }
    
    @Override
    public synchronized void initFromMemento(DownloadMemento memento) throws InvalidDataException {
        super.initFromMemento(memento);
        InNetworkDownloadMemento imem = (InNetworkDownloadMemento)memento;
        setTigerTreeRoot(imem.getTigerTreeRoot());
        setStartTime(imem.getStartTime());
        setDownloadAttempts(imem.getDownloadAttempts());
    }
}
