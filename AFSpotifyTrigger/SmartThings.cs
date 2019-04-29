namespace AFSpotifyTrigger
{
    public class Sensor
    {
        public string Name { get; set; }
        public string State { get; set; }
    }

    public class SpotifySettings
    {
        public string PlaylistURI { get; set; }
        public string PlaybackDevice { get; set; }
        public int PlaybackTrackNumber { get; set; }
        public int PlaybackVolume { get; set; } = 0;
        public int PlaybackDuration { get; set; } = 0;
        public int PlaybackTrackSeekPosition { get; set; } = 0;
    }

    public class SmartThings
    {
        public string Mode { get; set; }
        public Sensor Sensor { get; set; }
        public SpotifySettings SpotifySettings { get; set; }

        public SmartThings()
        {
            Sensor = new Sensor();
            SpotifySettings = new SpotifySettings();
        }
    }
}
