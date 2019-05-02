namespace HomeAutomation
{
    using AFSpotifyTrigger;
    using Microsoft.AspNetCore.Http;
    using Microsoft.AspNetCore.Mvc;
    using Microsoft.Azure.WebJobs;
    using Microsoft.Azure.WebJobs.Extensions.Http;
    using Microsoft.Extensions.Caching.Memory;
    using Microsoft.Extensions.Logging;
    using Newtonsoft.Json;
    using SpotifyAPI.Web;
    using SpotifyAPI.Web.Auth;
    using SpotifyAPI.Web.Enums;
    using SpotifyAPI.Web.Models;
    using System;
    using System.IO;
    using System.Linq;
    using System.Threading.Tasks;

    public static class SpotifyTrigger
    {
        private static MemoryCache memoryCache = new MemoryCache(new MemoryCacheOptions());

        [FunctionName("SpotifyTrigger")]
        public static async Task<IActionResult> Run([HttpTrigger(AuthorizationLevel.Function, "post", Route = null)] HttpRequest req, ILogger log)
        {
            string key = Environment.GetEnvironmentVariable("AccessKey", EnvironmentVariableTarget.Process);
            if (req.Headers["Authorization"] != key)
            {
                log.LogError("Missing secret");
                return new ForbidResult();
            }

            string client_id = Environment.GetEnvironmentVariable("ClientID", EnvironmentVariableTarget.Process);
            string client_secret = Environment.GetEnvironmentVariable("ClientSecret", EnvironmentVariableTarget.Process);
            string redirect_url = Environment.GetEnvironmentVariable("RedirectURI", EnvironmentVariableTarget.Process);
            string refreshToken = Environment.GetEnvironmentVariable("RefreshToken");

            string requestBody = await new StreamReader(req.Body).ReadToEndAsync();
            var st = JsonConvert.DeserializeObject<SmartThings>(requestBody);

            // Check we have something useful
            if (st == null || st.Sensor == null || st.SpotifySettings == null)
            {
                log.LogError("Missing sensor or state parameters");
                return new BadRequestResult();
            }
            log.LogInformation($"Sensor: {st.Sensor.Name} state: {st.Sensor.State} Playback device: {st.SpotifySettings.PlaybackDevice}");

            // Get a valid Spotify OAuth access token
            string accessToken = await GetAccessToken(log, client_id, client_secret, redirect_url, refreshToken);
            SpotifyWebAPI api = new SpotifyWebAPI { AccessToken = accessToken, TokenType = "Bearer" };

            // Hit Spotify API, make sure we get something back
            var devices = await api.GetDevicesAsync();
            if (devices.Error != null)
            {
                log.LogError(devices.Error.Message);
                return new BadRequestResult();
            }
            log.LogInformation($"Found {devices.Devices.Count()} devices");

            var targetDevice = devices.Devices.Where(n => n.Name.Equals(st.SpotifySettings.PlaybackDevice, StringComparison.InvariantCultureIgnoreCase)).FirstOrDefault();
            if (targetDevice == null)
            {
                log.LogError($"Device: {st.SpotifySettings.PlaybackDevice} not found or available");
                return new NotFoundResult();
            }
            log.LogInformation($"Target device: {st.SpotifySettings.PlaybackDevice} Playback Track: {st.SpotifySettings.PlaybackTrackNumber} Start Position: {st.SpotifySettings.PlaybackTrackSeekPosition}");

            // Add any other sensor/switch states here
            switch (st.Sensor.State.ToLowerInvariant())
            {
                case "stop":
                case "closed":
                case "inactive":
                    {
                        await api.PausePlaybackAsync(targetDevice.Id);
                        if (!await ReinstatePreviousSpotifySettings(log, api))
                        {
                            return new StatusCodeResult(StatusCodes.Status500InternalServerError);
                        }
                        break;
                    }
                case "start":
                case "open":
                case "active":
                    {
                        if (st.SpotifySettings.PlaybackVolume > 0)
                        {
                            // Stash current playing device and track to continue later
                            await StashCurrentSpotifySettings(api, devices);
                            await api.SetVolumeAsync(st.SpotifySettings.PlaybackVolume, targetDevice.Id);
                            await api.ResumePlaybackAsync(targetDevice.Id, contextUri: st.SpotifySettings.PlaylistURI, offset: st.SpotifySettings.PlaybackTrackNumber);
                            await api.SeekPlaybackAsync(st.SpotifySettings.PlaybackTrackSeekPosition, targetDevice.Id);

                            switch (st.Mode?.ToLowerInvariant())
                            {
                                case "game":
                                case "home":
                                    {
                                        if (st.SpotifySettings.PlaybackDuration > 0)
                                        {
                                            await Task.Delay(st.SpotifySettings.PlaybackDuration * 1000);
                                            await api.PausePlaybackAsync(targetDevice.Id);
                                            if (!await ReinstatePreviousSpotifySettings(log, api))
                                            {
                                                return new StatusCodeResult(StatusCodes.Status500InternalServerError);
                                            }
                                        }
                                    }
                                    break;
                                default:
                                    break;
                            }
                        }
                        break;
                    }
                default:
                    break;
            }

            return new OkResult();
        }

        private static async Task<string> GetAccessToken(ILogger log, string client_id, string client_secret, string redirect_url, string refreshToken)
        {
            if (!memoryCache.TryGetValue("AccessToken", out string cacheAccessToken))
            {
                AuthorizationCodeAuth auth = new AuthorizationCodeAuth(client_id, client_secret, redirect_url, redirect_url,
                    Scope.PlaylistReadPrivate | Scope.PlaylistReadCollaborative | Scope.UserModifyPlaybackState | Scope.UserReadPlaybackState);

                var token = await auth.RefreshToken(refreshToken);
                cacheAccessToken = token.AccessToken;
                memoryCache.Set("AccessToken", cacheAccessToken, TimeSpan.FromMinutes(50));
                log.LogInformation($"AccessToken updated in cache");
            }
            return cacheAccessToken;
        }

        private static async Task<bool> ReinstatePreviousSpotifySettings(ILogger log, SpotifyWebAPI api)
        {
            memoryCache.TryGetValue("PreviousContext", out PlaybackContext previousContext);
            memoryCache.TryGetValue("PreviousDevice", out Device previousDevice);
            memoryCache.TryGetValue("TrackOffset", out int? trackOffset);
            memoryCache.TryGetValue("PreviousVolume", out int? previousVolume);
            if (previousDevice != null && previousContext != null)
            {
                string spotifyUri = previousContext.Context == null && previousContext.CurrentlyPlayingType == TrackType.Track
                    ? previousContext.Item.Album.Uri
                    : previousContext.Context.Uri;

                ErrorResponse spotifyResponse = trackOffset != null
                    ? await api.ResumePlaybackAsync(previousDevice.Id, contextUri: spotifyUri, offset: trackOffset, positionMs: previousContext.ProgressMs)
                    : await api.ResumePlaybackAsync(previousDevice.Id, contextUri: spotifyUri, offset: "", positionMs: previousContext.ProgressMs);

                if (spotifyResponse.Error != null)
                {
                    log.LogError($"Error resuming playback: {spotifyResponse.Error.Message}");
                    return false;
                }

                if (!previousContext.IsPlaying)
                {
                    await api.PausePlaybackAsync(previousDevice.Id);
                }

                if (previousVolume != null)
                {
                    await api.SetVolumeAsync(previousVolume.Value);
                }
            }
            return true;
        }

        private static async Task StashCurrentSpotifySettings(SpotifyWebAPI api, AvailabeDevices devices)
        {
            var currentDevice = devices.Devices.Where(n => n.IsActive).FirstOrDefault();
            if (currentDevice != null)
            {
                memoryCache.Set("PreviousDevice", currentDevice);
                memoryCache.Set("PreviousContext", await api.GetPlayingTrackAsync());
                memoryCache.Set("PreviousVolume", currentDevice.VolumePercent);
                memoryCache.Remove("TrackOffset");

                var t = await api.GetPlaybackAsync();
                var profile = await api.GetPrivateProfileAsync();

                switch (t.Context?.Type.ToLowerInvariant())
                {
                    case "playlist":
                        {
                            // Try and find the current track position in the current playlist
                            string playlistId = t.Context.Uri.Remove(0, t.Context.Uri.LastIndexOf(':') + 1);
                            var playlistTracks = await api.GetPlaylistTracksAsync(playlistId);
                            for (int i = 0; i < playlistTracks?.Items?.Count; i++)
                            {
                                if (playlistTracks.Items[i].Track.Id == t.Item.Id)
                                {
                                    memoryCache.Set("TrackOffset", i);
                                    break;
                                }
                            }
                            break;
                        }
                    case "album":
                        {
                            if (!t.Context.Uri.Contains("collection", StringComparison.InvariantCultureIgnoreCase))
                            {
                                memoryCache.Set("TrackOffset", t.Item.TrackNumber - 1);
                            }
                            break;
                        }

                    default:
                        break;
                }
            }
        }
    }
}